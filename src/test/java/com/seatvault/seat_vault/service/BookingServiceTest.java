package com.seatvault.seat_vault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatvault.seat_vault.dto.CreateBookingRequest;
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
import com.seatvault.seat_vault.entity.Seat;
import com.seatvault.seat_vault.entity.User;
import com.seatvault.seat_vault.exception.ApiException;
import com.seatvault.seat_vault.repository.BookingRepository;
import com.seatvault.seat_vault.repository.BookingSeatRepository;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.HoldRepository;
import com.seatvault.seat_vault.repository.HoldSeatRepository;
import com.seatvault.seat_vault.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Pure-mock unit coverage of {@link BookingService}'s branching logic
 * (ownership/state validation, lazy-expiry rejection, idempotent-confirm
 * short-circuit, decline-releases-seats). The concurrency-correctness itself
 * (real row locking, real parallel confirm calls) is covered against a real
 * Postgres instance by {@code controller.BookingIntegrationTest} instead -
 * Mockito can't meaningfully exercise {@code SELECT ... FOR UPDATE} semantics.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private HoldRepository holdRepository;

    @Mock
    private HoldSeatRepository holdSeatRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingSeatRepository bookingSeatRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private EventSeatRepository eventSeatRepository;

    @Mock
    private PaymentService paymentService;

    private BookingService newBookingService() {
        return new BookingService(
                holdRepository, holdSeatRepository, bookingRepository, bookingSeatRepository, paymentRepository,
                eventSeatRepository, paymentService);
    }

    /**
     * Also pins an ordering hazard T-007 introduced: seat ids are now gathered
     * from {@code hold_seats} before the hold is read, and an unknown hold id
     * makes that lookup come back empty. The answer must still be this 404
     * (ADR-0008), not the "hold has no seats" 409 that an empty seat list
     * would otherwise produce.
     */
    @Test
    void createFromHoldWithMissingHoldIsNotFound() {
        BookingService service = newBookingService();
        when(holdRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createFromHold(1L, new CreateBookingRequest(999L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiException.getCode()).isEqualTo("HOLD_NOT_FOUND");
                });
    }

    @Test
    void createFromHoldOwnedBySomeoneElseIsNotFound() {
        BookingService service = newBookingService();
        Hold othersHold = Hold.builder()
                .id(10L)
                .user(User.builder().id(2L).build())
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(othersHold));

        assertThatThrownBy(() -> service.createFromHold(1L, new CreateBookingRequest(10L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiException.getCode()).isEqualTo("HOLD_NOT_FOUND");
                });
    }

    /**
     * The hold's status is checked under its own row lock, but that lock is
     * now taken <em>after</em> the seat locks, not before (T-007: locking the
     * holds row first put this method on the opposite side of the codebase's
     * lock ordering from {@code HoldService#createHold} and deadlocked against
     * it). So a doomed request does pay for the seat locks before being
     * rejected - asserted here rather than left to be discovered, since the
     * previous version of this test pinned the opposite.
     */
    @Test
    void createFromHoldWithNonActiveHoldIsRejectedAfterTheSeatsAreLocked() {
        BookingService service = newBookingService();
        Hold expiredHold = Hold.builder()
                .id(11L)
                .user(User.builder().id(1L).build())
                .status(HoldStatus.EXPIRED)
                .expiresAt(Instant.now().minusSeconds(60))
                .build();
        when(holdRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(expiredHold));

        EventSeat seat = EventSeat.builder()
                .id(12L)
                .status(EventSeatStatus.HELD)
                .price(new BigDecimal("50.00"))
                .currentHold(expiredHold)
                .build();
        when(holdSeatRepository.findByHoldId(11L)).thenReturn(List.of(HoldSeat.builder()
                .hold(expiredHold)
                .eventSeat(seat)
                .priceSnapshot(new BigDecimal("50.00"))
                .build()));
        when(eventSeatRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(seat));

        assertThatThrownBy(() -> service.createFromHold(1L, new CreateBookingRequest(11L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    // Stored status is EXPIRED - ADR-0009's split keys HOLD_EXPIRED
                    // vs HOLD_NOT_ACTIVE on domain state, not on which check fired.
                    assertThat(apiException.getCode()).isEqualTo("HOLD_EXPIRED");
                });

        verify(eventSeatRepository).findByIdForUpdate(12L);
        verify(bookingRepository, never()).save(any());
    }

    /**
     * The per-seat ownership guard T-007 added: a seat that an ACTIVE hold no
     * longer owns must not be booked out from under whoever does own it. The
     * invariant says this cannot happen (a seat only ever leaves a hold in the
     * transaction that also takes that hold out of ACTIVE) - the guard exists
     * because ADR-0010's whole point is that such a conclusion must be
     * re-checked under the seat's own row lock, not inherited from an earlier
     * read. Like the sibling test in {@code HoldServiceTest}, a mock cannot
     * reproduce the identity-map half of that rule; it pins the branch only.
     */
    @Test
    void createFromHoldDoesNotBookASeatThatNowBelongsToADifferentHold() {
        BookingService service = newBookingService();
        Hold ownedHold = Hold.builder()
                .id(32L)
                .user(User.builder().id(1L).build())
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(holdRepository.findByIdForUpdate(32L)).thenReturn(Optional.of(ownedHold));

        Hold otherHold = Hold.builder()
                .id(33L)
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        EventSeat seat = EventSeat.builder()
                .id(13L)
                .status(EventSeatStatus.HELD)
                .price(new BigDecimal("50.00"))
                .currentHold(otherHold)
                .build();
        when(holdSeatRepository.findByHoldId(32L)).thenReturn(List.of(HoldSeat.builder()
                .hold(ownedHold)
                .eventSeat(seat)
                .priceSnapshot(new BigDecimal("50.00"))
                .build()));
        when(eventSeatRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(seat));

        assertThatThrownBy(() -> service.createFromHold(1L, new CreateBookingRequest(32L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    // ownedHold is not time-expired (expiresAt is +300s), so
                    // ADR-0009's HoldExpiry-keyed split falls through to the
                    // defensive HOLD_NOT_ACTIVE answer here, not HOLD_EXPIRED.
                    assertThat(apiException.getCode()).isEqualTo("HOLD_NOT_ACTIVE");
                });

        assertThat(seat.getStatus()).isEqualTo(EventSeatStatus.HELD);
        assertThat(seat.getCurrentHold()).isEqualTo(otherHold);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createFromHoldRejectsWhenHoldHasLazilyExpiredSinceCreation() {
        BookingService service = newBookingService();
        Hold ownedHold = Hold.builder()
                .id(30L)
                .user(User.builder().id(1L).build())
                .status(HoldStatus.ACTIVE)
                // Stored as ACTIVE (not yet swept) but already past its TTL -
                // the authoritative per-seat check below must catch this.
                .expiresAt(Instant.now().minusSeconds(1))
                .build();
        when(holdRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(ownedHold));

        EventSeat seat = EventSeat.builder()
                .id(7L)
                .status(EventSeatStatus.HELD)
                .price(new BigDecimal("50.00"))
                .currentHold(ownedHold)
                .build();
        HoldSeat holdSeat = HoldSeat.builder()
                .hold(ownedHold)
                .eventSeat(seat)
                .priceSnapshot(new BigDecimal("50.00"))
                .build();
        when(holdSeatRepository.findByHoldId(30L)).thenReturn(List.of(holdSeat));
        when(eventSeatRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(seat));

        assertThatThrownBy(() -> service.createFromHold(1L, new CreateBookingRequest(30L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    // Stored ACTIVE but past its TTL - the lazy-expiry site
                    // (BookingService:154-ish) reports HOLD_EXPIRED per ADR-0009,
                    // the same code a stored-EXPIRED hold reports.
                    assertThat(apiException.getCode()).isEqualTo("HOLD_EXPIRED");
                });

        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createFromHoldWithNoHoldSeatsIsRejected() {
        BookingService service = newBookingService();
        Hold ownedHold = Hold.builder()
                .id(31L)
                .user(User.builder().id(1L).build())
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(holdRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(ownedHold));
        when(holdSeatRepository.findByHoldId(31L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.createFromHold(1L, new CreateBookingRequest(31L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiException.getCode()).isEqualTo("HOLD_NOT_ACTIVE");
                });

        verify(eventSeatRepository, never()).findByIdForUpdate(anyLong());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void confirmPaymentWithMissingBookingIsNotFound() {
        BookingService service = newBookingService();
        when(bookingRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmPayment(1L, 999L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiException.getCode()).isEqualTo("BOOKING_NOT_FOUND");
                });
    }

    @Test
    void confirmPaymentOwnedBySomeoneElseIsNotFound() {
        BookingService service = newBookingService();
        Booking othersBooking = Booking.builder()
                .id(40L)
                .user(User.builder().id(2L).build())
                .status(BookingStatus.PENDING)
                .build();
        when(bookingRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(othersBooking));

        assertThatThrownBy(() -> service.confirmPayment(1L, 40L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiException.getCode()).isEqualTo("BOOKING_NOT_FOUND");
                });
    }

    @Test
    void confirmPaymentShortCircuitsOnNonPendingBookingWithoutChargingAgain() {
        BookingService service = newBookingService();
        Booking confirmedBooking = Booking.builder()
                .id(41L)
                .user(User.builder().id(1L).build())
                .status(BookingStatus.CONFIRMED)
                .createdAt(Instant.now())
                .build();
        when(bookingRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(confirmedBooking));
        when(bookingSeatRepository.findByBookingId(41L)).thenReturn(List.of());
        when(paymentRepository.findByBookingId(41L)).thenReturn(Optional.of(Payment.builder()
                .id(90L)
                .status(PaymentStatus.SUCCEEDED)
                .amount(new BigDecimal("50.00"))
                .build()));

        service.confirmPayment(1L, 41L);

        verify(paymentService, never()).charge(any(), any());
    }

    @Test
    void confirmPaymentDeclineReleasesAllBookingSeats() {
        BookingService service = newBookingService();
        Booking pendingBooking = Booking.builder()
                .id(42L)
                .user(User.builder().id(1L).build())
                .status(BookingStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        when(bookingRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(pendingBooking));

        Payment payment = Payment.builder()
                .id(91L)
                .status(PaymentStatus.PENDING)
                .amount(new BigDecimal("50.00"))
                .build();
        when(paymentRepository.findByBookingId(42L)).thenReturn(Optional.of(payment));
        when(paymentService.charge(pendingBooking, new BigDecimal("50.00"))).thenReturn(PaymentStatus.FAILED);

        Seat rawSeat = Seat.builder().section("A").rowLabel("A").seatNumber(1).build();
        EventSeat eventSeat = EventSeat.builder()
                .id(8L)
                .seat(rawSeat)
                .status(EventSeatStatus.BOOKED)
                .price(new BigDecimal("50.00"))
                .build();
        BookingSeat bookingSeat = BookingSeat.builder()
                .booking(pendingBooking)
                .eventSeat(eventSeat)
                .priceSnapshot(new BigDecimal("50.00"))
                .build();
        when(bookingSeatRepository.findByBookingId(42L)).thenReturn(List.of(bookingSeat));
        when(eventSeatRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(eventSeat));

        service.confirmPayment(1L, 42L);

        assertThat(pendingBooking.getStatus()).isEqualTo(BookingStatus.FAILED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(eventSeat.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(eventSeat.getCurrentHold()).isNull();
    }

    /**
     * PaymentService's contract (see its Javadoc) says only SUCCEEDED or
     * FAILED are valid synchronous outcomes. A misbehaving implementation
     * returning PENDING must fail loudly rather than being silently
     * misread as a decline (which would release seats out from under a
     * charge that might still be in flight).
     */
    @Test
    void confirmPaymentRejectsAnInvalidPaymentServiceOutcome() {
        BookingService service = newBookingService();
        Booking pendingBooking = Booking.builder()
                .id(43L)
                .user(User.builder().id(1L).build())
                .status(BookingStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        when(bookingRepository.findByIdForUpdate(43L)).thenReturn(Optional.of(pendingBooking));

        Payment payment = Payment.builder()
                .id(92L)
                .status(PaymentStatus.PENDING)
                .amount(new BigDecimal("50.00"))
                .build();
        when(paymentRepository.findByBookingId(43L)).thenReturn(Optional.of(payment));
        when(paymentService.charge(pendingBooking, new BigDecimal("50.00"))).thenReturn(PaymentStatus.PENDING);

        assertThatThrownBy(() -> service.confirmPayment(1L, 43L)).isInstanceOf(IllegalStateException.class);

        verify(bookingSeatRepository, never()).findByBookingId(43L);
        assertThat(pendingBooking.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void cancelWithMissingBookingIsNotFound() {
        BookingService service = newBookingService();
        when(bookingRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(1L, 999L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiException.getCode()).isEqualTo("BOOKING_NOT_FOUND");
                });

        verify(bookingSeatRepository, never()).findByBookingId(anyLong());
    }

    // ADR-0008: a booking owned by someone else collapses into the same 404
    // as a nonexistent booking id, not a 403 - callers must not learn that a
    // given booking id exists at all.
    @Test
    void cancelOwnedBySomeoneElseIsNotFound() {
        BookingService service = newBookingService();
        Booking othersBooking = Booking.builder()
                .id(50L)
                .user(User.builder().id(2L).build())
                .status(BookingStatus.CONFIRMED)
                .build();
        when(bookingRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(othersBooking));

        assertThatThrownBy(() -> service.cancel(1L, 50L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiException.getCode()).isEqualTo("BOOKING_NOT_FOUND");
                });

        verify(bookingSeatRepository, never()).findByBookingId(anyLong());
    }

    @Test
    void cancelOnPendingBookingIsRejectedWithoutSideEffects() {
        assertCancelOnNonConfirmedBookingIsRejected(51L, BookingStatus.PENDING);
    }

    @Test
    void cancelOnFailedBookingIsRejectedWithoutSideEffects() {
        assertCancelOnNonConfirmedBookingIsRejected(52L, BookingStatus.FAILED);
    }

    @Test
    void cancelOnAlreadyCancelledBookingIsRejectedWithoutSideEffects() {
        assertCancelOnNonConfirmedBookingIsRejected(53L, BookingStatus.CANCELLED);
    }

    private void assertCancelOnNonConfirmedBookingIsRejected(long bookingId, BookingStatus status) {
        BookingService service = newBookingService();
        Booking booking = Booking.builder()
                .id(bookingId)
                .user(User.builder().id(1L).build())
                .status(status)
                .build();
        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancel(1L, bookingId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiException.getCode()).isEqualTo("BOOKING_NOT_CONFIRMED");
                });

        assertThat(booking.getStatus()).isEqualTo(status);
        verify(bookingSeatRepository, never()).findByBookingId(anyLong());
        verify(eventSeatRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void cancelOnConfirmedBookingReleasesSeatsAndMarksCancelled() {
        BookingService service = newBookingService();
        Booking confirmedBooking = Booking.builder()
                .id(60L)
                .user(User.builder().id(1L).build())
                .status(BookingStatus.CONFIRMED)
                .createdAt(Instant.now())
                .build();
        when(bookingRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(confirmedBooking));

        Seat rawSeat = Seat.builder().section("A").rowLabel("A").seatNumber(1).build();
        EventSeat eventSeat = EventSeat.builder()
                .id(9L)
                .seat(rawSeat)
                .status(EventSeatStatus.BOOKED)
                .price(new BigDecimal("50.00"))
                .build();
        BookingSeat bookingSeat = BookingSeat.builder()
                .booking(confirmedBooking)
                .eventSeat(eventSeat)
                .priceSnapshot(new BigDecimal("50.00"))
                .build();
        when(bookingSeatRepository.findByBookingId(60L)).thenReturn(List.of(bookingSeat));
        when(eventSeatRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(eventSeat));
        when(paymentRepository.findByBookingId(60L)).thenReturn(Optional.of(Payment.builder()
                .id(93L)
                .status(PaymentStatus.SUCCEEDED)
                .amount(new BigDecimal("50.00"))
                .build()));

        service.cancel(1L, 60L);

        assertThat(confirmedBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(eventSeat.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(eventSeat.getCurrentHold()).isNull();
        verify(eventSeatRepository).findByIdForUpdate(9L);
        // No refund concept (CONTEXT.md) - the Payment itself is never touched.
        verify(paymentService, never()).charge(any(), any());
    }
}
