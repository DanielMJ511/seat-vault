package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.HoldRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * UX-only reconciliation per ADR-0002: nothing here is required for
 * correctness (that's {@code HoldService}'s lazy check-on-read), this just
 * keeps stored {@code event_seats}/{@code holds} state from looking stale to
 * a casual browser who isn't actively trying to hold a seat.
 */
@Component
@RequiredArgsConstructor
public class HoldSweepService {

    private final EventSeatRepository eventSeatRepository;
    private final HoldRepository holdRepository;

    /**
     * The order of these statements is load-bearing, not stylistic, on two
     * axes at once (both closed by ADR-0011).
     *
     * <p><b>Table order.</b> The seat-locking and hold-expiring statements
     * run in one transaction and match the same expired holds by
     * construction (same {@code now}, complementary predicates), so between
     * them this method locks both {@code event_seats} rows and those seats'
     * {@code holds} row(s) - and it must take them in the order every other
     * path takes them: <b>seats first, holds second</b>. Reordering these
     * calls, or splitting them into two transactions in a way that reverses
     * their effective order, re-creates the ABBA deadlock T-007 fixed - this
     * method being on a 30-second timer, it would fire unattended in
     * production rather than needing a user to trigger it.
     *
     * <p><b>Seat order.</b> {@link EventSeatRepository#findIdsOfExpiredHeldSeatsForUpdate}
     * locks the matched {@code event_seats} rows in ascending id order - the
     * same discipline every other multi-seat path uses - before {@link
     * EventSeatRepository#releaseExpiredHeldSeats} re-touches exactly those
     * rows. Going back to a single unordered bulk {@code UPDATE} would
     * re-open the seat-versus-seat residual M8 (#16) closed: this method
     * locking two seats in whatever order Postgres's plan produced them,
     * against a concurrent multi-seat {@code createHold} locking the same
     * two seats the other way.
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void sweepExpiredHolds() {
        Instant now = Instant.now();
        List<Long> expiredSeatIds = eventSeatRepository.findIdsOfExpiredHeldSeatsForUpdate(now);
        if (!expiredSeatIds.isEmpty()) {
            eventSeatRepository.releaseExpiredHeldSeats(expiredSeatIds, now);
        }
        holdRepository.expireOverdueHolds(now);
    }
}
