package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.dto.BookingResponse;
import com.seatvault.seat_vault.dto.BookingSeatResponse;
import com.seatvault.seat_vault.dto.CreateBookingRequest;
import com.seatvault.seat_vault.dto.PaymentResponse;
import com.seatvault.seat_vault.entity.Booking;
import com.seatvault.seat_vault.entity.BookingSeat;
import com.seatvault.seat_vault.entity.BookingStatus;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import com.seatvault.seat_vault.entity.Hold;
import com.seatvault.seat_vault.entity.HoldSeat;
import com.seatvault.seat_vault.entity.HoldStatus;
import com.seatvault.seat_vault.entity.Payment;
import com.seatvault.seat_vault.entity.PaymentStatus;
import com.seatvault.seat_vault.exception.ApiException;
import com.seatvault.seat_vault.repository.BookingRepository;
import com.seatvault.seat_vault.repository.BookingSeatRepository;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.HoldRepository;
import com.seatvault.seat_vault.repository.HoldSeatRepository;
import com.seatvault.seat_vault.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Hold-to-Booking-to-Payment path. {@code createFromHold} reuses the same
 * row-locking discipline {@code HoldService} established (ADR-0001:
 * {@link EventSeatRepository#findByIdForUpdate(Long)} is the only correct way
 * to read-then-mutate an EventSeat; ADR-0002: expiry is checked authoritatively
 * inside that same locked transaction via {@link EventSeatAvailability},
 * never by trusting an unlocked {@code Hold.expiresAt} read; ADR-0003: price is
 * carried through unchanged from the HoldSeat snapshot, never re-read from
 * EventSeat). {@code confirmPayment} adds its own serialization point - a row
 * lock on the Booking itself - to make repeated confirm calls idempotent.
 *
 * <p>ADR-0011 governs the order those locks are taken in: {@code
 * createFromHold} locks its seats before the hold, matching {@code
 * HoldService}. The Booking row lock in {@code confirmPayment}/{@code cancel}
 * sits ahead of both - bookings, then seats, then holds - which is consistent
 * because nothing ever locks an existing Booking while already holding a seat.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private final HoldRepository holdRepository;
    private final HoldSeatRepository holdSeatRepository;
    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final PaymentRepository paymentRepository;
    private final EventSeatRepository eventSeatRepository;
    private final PaymentService paymentService;

    /**
     * Same lock order as {@code HoldService#releaseHold}, and for the same
     * reason: <b>every {@code event_seats} row first (ascending id), the
     * {@code holds} row second</b> (ADR-0011). This method used to lock the
     * hold first to authorize the caller, which put it on the opposite side of
     * the ordering from {@code HoldService#createHold} and {@code
     * HoldSweepService} - both of which lock a seat and then write that seat's
     * (expired) hold. The interleaving is entirely reachable: a user clicking
     * "book" on a hold that has just lazily expired, while someone else grabs
     * the freed seat, is two ordinary requests, and Postgres resolves the
     * resulting cycle by killing one of them with a 500.
     *
     * <p>Authorization is unchanged in substance - a hold that does not exist
     * and a hold belonging to someone else still collapse into the same 404
     * (ADR-0008), and a non-ACTIVE hold still gets 409 - it just happens after
     * the seats are pinned rather than before, so a rejected request may
     * briefly have held seat locks it then rolls back.
     */
    @Transactional
    public BookingResponse createFromHold(Long userId, CreateBookingRequest request) {
        // Gathered before any lock, and deliberately without reading the Hold
        // itself: this is a query on hold_seats by hold id, and HoldSeat#hold
        // is a lazy proxy that stays untouched, so nothing puts a pre-lock
        // snapshot of the Hold into the persistence context ahead of the
        // locked read below (ADR-0010). HoldSeat#eventSeat is likewise only
        // asked for its identifier, which a proxy answers without loading.
        // The set is immutable in practice - hold_seats rows are written once,
        // when the hold is created, and never added to.
        List<HoldSeat> holdSeats = holdSeatRepository.findByHoldId(request.holdId());
        Map<Long, HoldSeat> holdSeatByEventSeatId = new HashMap<>();
        for (HoldSeat holdSeat : holdSeats) {
            holdSeatByEventSeatId.put(holdSeat.getEventSeat().getId(), holdSeat);
        }
        List<Long> seatIds = holdSeatByEventSeatId.keySet().stream().sorted().toList();

        List<EventSeat> lockedSeats = new ArrayList<>(seatIds.size());
        for (Long seatId : seatIds) {
            // The actual correctness mechanism: a Postgres row lock, held
            // until this transaction commits or rolls back.
            lockedSeats.add(eventSeatRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_SEAT_NOT_FOUND",
                            "Event seat " + seatId + " not found.")));
        }

        // Only now the holds row. Locked (not a plain findById) so this can't
        // race with HoldService#releaseHold on the same Hold: whichever of the
        // two commits first, the other blocks here and re-reads the committed
        // status below, rather than a stale in-memory copy overwriting it.
        Hold hold = holdRepository.findByIdForUpdate(request.holdId())
                .filter(h -> h.getUser().getId().equals(userId))
                // Same code whether the hold doesn't exist at all or belongs
                // to someone else - don't let a non-owner distinguish the two.
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "Hold not found."));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Hold is not active.");
        }
        if (lockedSeats.isEmpty()) {
            // Shouldn't happen - HoldService#createHold never persists a Hold
            // without at least one HoldSeat - but an ACTIVE Hold with no
            // seats can't be converted into a meaningful Booking, so reject
            // rather than silently producing a $0, zero-seat one. Checked
            // after the two lookups above so an unknown or foreign hold id
            // still answers 404 rather than leaking through as this 409.
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Hold has no seats.");
        }

        List<BookingSeat> bookingSeats = new ArrayList<>(lockedSeats.size());
        for (EventSeat eventSeat : lockedSeats) {
            // Checked before the availability check, and before anything
            // initializes the association: getId() reads the proxy's
            // identifier only. The invariant this defends is that a seat only
            // ever leaves a hold in the same transaction that takes that hold
            // out of ACTIVE, so an ACTIVE hold whose seat points elsewhere
            // should be impossible - but the whole point of ADR-0010 is that
            // the guard must be under the seat's own lock rather than assumed
            // from an earlier read.
            Hold seatHold = eventSeat.getCurrentHold();
            if (seatHold == null || !seatHold.getId().equals(hold.getId())) {
                throw new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Hold is not active.");
            }

            EventSeatStatus effectiveStatus = EventSeatAvailability.effectiveStatus(eventSeat);
            if (effectiveStatus != EventSeatStatus.HELD) {
                // AVAILABLE means this hold lazily expired since it was
                // created (ADR-0002) - this transaction is rolling back, so
                // there's no point reconciling Hold.status here, the
                // sweep/lazy-check will catch it later. BOOKED is unreachable
                // via the guard above (a booked seat carries no currentHold),
                // but reject defensively rather than silently trusting that.
                throw new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Hold is not active.");
            }

            eventSeat.setStatus(EventSeatStatus.BOOKED);
            eventSeat.setCurrentHold(null);

            HoldSeat holdSeat = holdSeatByEventSeatId.get(eventSeat.getId());
            bookingSeats.add(BookingSeat.builder()
                    .eventSeat(eventSeat)
                    .priceSnapshot(holdSeat.getPriceSnapshot())
                    .build());
        }

        hold.setStatus(HoldStatus.CONVERTED);

        Booking booking = bookingRepository.save(Booking.builder()
                .user(hold.getUser())
                .hold(hold)
                .status(BookingStatus.PENDING)
                .build());
        bookingSeats.forEach(bookingSeat -> bookingSeat.setBooking(booking));
        bookingSeats = bookingSeatRepository.saveAll(bookingSeats);

        BigDecimal total = bookingSeats.stream()
                .map(BookingSeat::getPriceSnapshot)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Payment payment = paymentRepository.save(Payment.builder()
                .booking(booking)
                .status(PaymentStatus.PENDING)
                .amount(total)
                .build());

        return toResponse(booking, bookingSeats, payment);
    }

    /**
     * Note: the Booking row lock below is held for the full duration of the
     * {@link PaymentService#charge} call, blocking any other request against
     * this Booking (including the client's own retry) for as long as
     * charging takes. Acceptable for {@link SimulatedPaymentServiceImpl}'s
     * in-memory call; a future real, network-calling provider would need to
     * revisit this (e.g. release the lock before charging and re-acquire it
     * under a fresh idempotency check before writing the outcome).
     */
    @Transactional
    public BookingResponse confirmPayment(Long userId, Long bookingId) {
        // The sole serialization point for this whole state machine: nothing
        // else touches this Booking's linked Payment without first holding
        // this row lock.
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .filter(b -> b.getUser().getId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found."));

        if (booking.getStatus() != BookingStatus.PENDING) {
            // A repeat confirm call - a legitimate client retry (e.g. after a
            // timeout), not an error. Return the current state as-is rather
            // than charging again.
            return toResponse(booking);
        }

        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found."));

        PaymentStatus outcome = paymentService.charge(booking, payment.getAmount());
        if (outcome != PaymentStatus.SUCCEEDED && outcome != PaymentStatus.FAILED) {
            // A PaymentService implementation bug, not a client error - per
            // its contract (see PaymentService's Javadoc), PENDING is never a
            // valid synchronous return value. Fail loudly rather than
            // misreading it as a decline and releasing seats out from under
            // a charge that might still be in flight.
            throw new IllegalStateException(
                    "PaymentService returned " + outcome + " for booking " + bookingId + "; only SUCCEEDED or "
                            + "FAILED are valid synchronous outcomes.");
        }
        payment.setStatus(outcome);

        if (outcome == PaymentStatus.SUCCEEDED) {
            booking.setStatus(BookingStatus.CONFIRMED);
        } else {
            booking.setStatus(BookingStatus.FAILED);
            releaseBookingSeats(bookingId);
        }

        return toResponse(booking);
    }

    /**
     * Mirrors {@code confirmPayment}'s serialization point: the Booking row
     * lock is the only thing that needs to guard against a double-cancel race
     * (two concurrent cancel calls on the same booking) - whichever call
     * commits first flips the status to CANCELLED, and the other blocks here
     * then re-reads that committed status and rejects with 409 below, rather
     * than releasing the same seats twice.
     */
    @Transactional
    public BookingResponse cancel(Long userId, Long bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .filter(b -> b.getUser().getId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found."));

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ApiException(HttpStatus.CONFLICT, "BOOKING_NOT_CONFIRMED", "Booking is not confirmed.");
        }

        // Hold is already CONVERTED and Payment stays SUCCEEDED - CONTEXT.md
        // has no refund concept, so neither is touched here.
        releaseBookingSeats(bookingId);
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setUpdatedAt(Instant.now());

        return toResponse(booking);
    }

    /**
     * Read-only, so unlike {@code confirmPayment}/{@code cancel} this does not
     * take a row lock via {@code findByIdForUpdate} - there's no read-then-mutate
     * step here to protect, just a single consistent read. Ownership mismatch and
     * not-found are collapsed into the same 404 (ADR-0008).
     */
    @Transactional(readOnly = true)
    public BookingResponse getBooking(Long userId, Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .filter(b -> b.getUser().getId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found."));

        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listBookings(Long userId) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    void releaseBookingSeats(Long bookingId) {
        List<Long> seatIds = bookingSeatRepository.findByBookingId(bookingId).stream()
                .map(bookingSeat -> bookingSeat.getEventSeat().getId())
                .sorted()
                .toList();

        for (Long seatId : seatIds) {
            EventSeat eventSeat = eventSeatRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_SEAT_NOT_FOUND",
                            "Event seat " + seatId + " not found."));
            // Defensive, mirroring HoldService#releaseHold's ownership guard:
            // this booking is the only thing that should ever hold a seat in
            // BOOKED (createFromHold clears currentHold when it sets it), so
            // this should always be true - but fail loudly instead of
            // silently stealing a seat if that invariant is ever violated.
            if (eventSeat.getStatus() != EventSeatStatus.BOOKED) {
                throw new IllegalStateException(
                        "Event seat " + seatId + " for booking " + bookingId + " was expected to be BOOKED but was "
                                + eventSeat.getStatus() + ".");
            }
            eventSeat.setStatus(EventSeatStatus.AVAILABLE);
            eventSeat.setCurrentHold(null);
        }
    }

    private BookingResponse toResponse(Booking booking) {
        List<BookingSeat> bookingSeats = bookingSeatRepository.findByBookingId(booking.getId());
        Payment payment = paymentRepository.findByBookingId(booking.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found."));
        return toResponse(booking, bookingSeats, payment);
    }

    private BookingResponse toResponse(Booking booking, List<BookingSeat> bookingSeats, Payment payment) {
        List<BookingSeatResponse> seats = bookingSeats.stream()
                .map(bookingSeat -> new BookingSeatResponse(
                        bookingSeat.getEventSeat().getId(),
                        bookingSeat.getEventSeat().getSeat().getSection(),
                        bookingSeat.getEventSeat().getSeat().getRowLabel(),
                        bookingSeat.getEventSeat().getSeat().getSeatNumber(),
                        bookingSeat.getPriceSnapshot()))
                .toList();

        PaymentResponse paymentResponse = new PaymentResponse(payment.getId(), payment.getStatus(), payment.getAmount());
        return new BookingResponse(booking.getId(), booking.getStatus(), booking.getCreatedAt(), seats, paymentResponse);
    }
}
