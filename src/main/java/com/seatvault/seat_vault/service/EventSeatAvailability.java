package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import java.time.Instant;

/**
 * Stateless helper implementing SeatVault's lazy-expiry rule: a seat's status
 * as stored in {@code event_seats} isn't flipped back to {@code AVAILABLE}
 * the instant a hold expires (there's no scheduled job in the request path
 * for that) — instead, any reader resolves the *effective* status on the fly
 * by comparing {@code currentHold.expiresAt} against "now". This class is the
 * single place that logic lives so M4's hold engine can reuse it verbatim
 * rather than reimplementing the same check.
 *
 * <p>Deliberately not a Spring bean: it's a pure function with no
 * dependencies, so plain static access keeps it trivially reusable and
 * testable without a Spring context.
 */
public final class EventSeatAvailability {

    private EventSeatAvailability() {
    }

    public static EventSeatStatus effectiveStatus(EventSeat eventSeat) {
        if (eventSeat.getStatus() != EventSeatStatus.HELD) {
            return eventSeat.getStatus();
        }

        if (eventSeat.getCurrentHold() == null) {
            // Defensive: HELD should always carry a currentHold pointer.
            // Shouldn't occur in practice, but fail safe rather than throw.
            return EventSeatStatus.HELD;
        }

        if (eventSeat.getCurrentHold().getExpiresAt().isBefore(Instant.now())) {
            return EventSeatStatus.AVAILABLE;
        }

        return EventSeatStatus.HELD;
    }
}
