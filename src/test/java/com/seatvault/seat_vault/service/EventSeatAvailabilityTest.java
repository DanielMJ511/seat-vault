package com.seatvault.seat_vault.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import com.seatvault.seat_vault.entity.Hold;
import com.seatvault.seat_vault.entity.HoldStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit coverage of {@link EventSeatAvailability#effectiveStatus}: a
 * pure static function with no Spring dependencies, so no Spring context is
 * needed to exercise it.
 */
class EventSeatAvailabilityTest {

    @Test
    void availableSeatStaysAvailable() {
        EventSeat eventSeat = EventSeat.builder()
                .id(1L)
                .status(EventSeatStatus.AVAILABLE)
                .price(BigDecimal.TEN)
                .build();

        assertThat(EventSeatAvailability.effectiveStatus(eventSeat)).isEqualTo(EventSeatStatus.AVAILABLE);
    }

    @Test
    void bookedSeatStaysBooked() {
        EventSeat eventSeat = EventSeat.builder()
                .id(1L)
                .status(EventSeatStatus.BOOKED)
                .price(BigDecimal.TEN)
                .build();

        assertThat(EventSeatAvailability.effectiveStatus(eventSeat)).isEqualTo(EventSeatStatus.BOOKED);
    }

    @Test
    void heldSeatWithFutureExpiryStaysHeld() {
        Hold activeHold = Hold.builder()
                .id(1L)
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        EventSeat eventSeat = EventSeat.builder()
                .id(1L)
                .status(EventSeatStatus.HELD)
                .currentHold(activeHold)
                .price(BigDecimal.TEN)
                .build();

        assertThat(EventSeatAvailability.effectiveStatus(eventSeat)).isEqualTo(EventSeatStatus.HELD);
    }

    @Test
    void heldSeatWithExpiredHoldIsEffectivelyAvailableWithoutMutatingTheEntity() {
        Hold expiredHold = Hold.builder()
                .id(1L)
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().minusSeconds(60))
                .build();
        EventSeat eventSeat = EventSeat.builder()
                .id(1L)
                .status(EventSeatStatus.HELD)
                .currentHold(expiredHold)
                .price(BigDecimal.TEN)
                .build();

        assertThat(EventSeatAvailability.effectiveStatus(eventSeat)).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(eventSeat.getStatus()).isEqualTo(EventSeatStatus.HELD);
    }

    @Test
    void heldSeatWithNoCurrentHoldDefensivelyStaysHeld() {
        EventSeat eventSeat = EventSeat.builder()
                .id(1L)
                .status(EventSeatStatus.HELD)
                .currentHold(null)
                .price(BigDecimal.TEN)
                .build();

        assertThat(EventSeatAvailability.effectiveStatus(eventSeat)).isEqualTo(EventSeatStatus.HELD);
    }
}
