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

    @Transactional
    public BookingResponse createFromHold(Long userId, CreateBookingRequest request) {
        // Locked (not a plain findById) so this can't race with
        // HoldService#releaseHold on the same Hold: whichever of the two
        // commits first, the other blocks here and re-reads the committed
        // status below, rather than a stale in-memory copy overwriting it.
        Hold hold = holdRepository.findByIdForUpdate(request.holdId())
                .filter(h -> h.getUser().getId().equals(userId))
                // Same code whether the hold doesn't exist at all or belongs
                // to someone else - don't let a non-owner distinguish the two.
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "Hold not found."));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Hold is not active.");
        }

        List<HoldSeat> holdSeats = holdSeatRepository.findByHoldId(hold.getId());
        if (holdSeats.isEmpty()) {
            // Shouldn't happen - HoldService#createHold never persists a Hold
            // without at least one HoldSeat - but an ACTIVE Hold with no
            // seats can't be converted into a meaningful Booking, so reject
            // rather than silently producing a $0, zero-seat one.
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Hold has no seats.");
        }
        Map<Long, HoldSeat> holdSeatByEventSeatId = new HashMap<>();
        for (HoldSeat holdSeat : holdSeats) {
            holdSeatByEventSeatId.put(holdSeat.getEventSeat().getId(), holdSeat);
        }
        List<Long> seatIds = holdSeatByEventSeatId.keySet().stream().sorted().toList();

        List<BookingSeat> bookingSeats = new ArrayList<>(seatIds.size());
        for (Long seatId : seatIds) {
            // The actual correctness mechanism: a Postgres row lock, held
            // until this transaction commits or rolls back.
            EventSeat eventSeat = eventSeatRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_SEAT_NOT_FOUND",
                            "Event seat " + seatId + " not found."));

            EventSeatStatus effectiveStatus = EventSeatAvailability.effectiveStatus(eventSeat);
            if (effectiveStatus != EventSeatStatus.HELD) {
                // AVAILABLE means this hold lazily expired since it was
                // created (ADR-0002) - this transaction is rolling back, so
                // there's no point reconciling Hold.status here, the
                // sweep/lazy-check will catch it later. BOOKED should be
                // unreachable now that the Hold row itself is locked above
                // before this loop runs - any concurrent createFromHold call
                // racing on the same Hold now serializes there, before
                // either can touch a seat - but reject defensively rather
                // than silently trusting that assumption.
                throw new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Hold is not active.");
            }

            eventSeat.setStatus(EventSeatStatus.BOOKED);
            eventSeat.setCurrentHold(null);

            HoldSeat holdSeat = holdSeatByEventSeatId.get(seatId);
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

    private void releaseBookingSeats(Long bookingId) {
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
