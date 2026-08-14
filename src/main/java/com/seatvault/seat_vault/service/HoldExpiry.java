package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.entity.Hold;
import com.seatvault.seat_vault.entity.HoldStatus;
import java.time.Instant;

/**
 * Stateless helper answering "is this Hold expired?" per ADR-0002's
 * lazy-expiry rule and ADR-0007's decision to store a manual release
 * identically to a timed-out hold: a Hold counts as expired whether its
 * stored status has already been flipped to {@code EXPIRED} (by the sweep or
 * a manual release) or it is still stored {@code ACTIVE} with {@code
 * expiresAt} in the past and nothing has reconciled it yet.
 *
 * <p>This is the helper ADR-0009's {@code HOLD_EXPIRED}/{@code
 * HOLD_NOT_ACTIVE} split is keyed on: callers must decide using this, never
 * by which check happened to fire, or the response would leak whether {@code
 * HoldSweepService} had already run - exactly the nondeterminism ADR-0002's
 * lazy-expiry design exists to hide.
 *
 * <p>Sibling of {@link EventSeatAvailability}, which answers the equivalent
 * question for an {@code EventSeat}: deliberately not a Spring bean, a pure
 * function with no dependencies, for the same reason - trivially reusable and
 * testable without a Spring context.
 */
public final class HoldExpiry {

    private HoldExpiry() {
    }

    public static boolean isExpired(Hold hold) {
        if (hold.getStatus() == HoldStatus.EXPIRED) {
            return true;
        }
        return hold.getStatus() == HoldStatus.ACTIVE && hold.getExpiresAt().isBefore(Instant.now());
    }
}
