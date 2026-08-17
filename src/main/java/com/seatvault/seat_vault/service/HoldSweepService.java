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
    private final SweepMetrics sweepMetrics;

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
     *
     * <p><b>Metrics (T-005).</b> {@link SweepMetrics#recordSweep} runs after
     * both statements, purely in-memory against Micrometer's registry - it
     * does not touch either table, so it cannot change what gets locked or
     * when, and does not reorder, split, or wrap the two calls above.
     * {@code expiredSeatIds.size()} is recorded for the reclaimed-seats
     * count rather than {@link EventSeatRepository#releaseExpiredHeldSeats}'s
     * own row-count return value: the two numbers describe the same set
     * (every id in {@code expiredSeatIds} was locked by the projection, so
     * nothing can change it before the {@code UPDATE} re-touches it), and
     * {@code size()} is available unconditionally - including on the
     * empty-list branch below, where the {@code UPDATE} is skipped entirely
     * and a row-count variable would not exist. Recording {@code size()}
     * therefore records a true {@code 0} on an idle sweep rather than
     * silently skipping the metric on exactly the branch this design is
     * most likely to get wrong.
     *
     * <p>Per ADR-0002, the sweep is UX-only reconciliation: a reader
     * normally reconciles an expired hold lazily, on read, before the sweep
     * ever gets to it, so the sweep should usually find little to do. That
     * is a testable prediction, and recording every sweep - including the
     * zeros - is what makes it testable: if these numbers show sweeps
     * routinely reclaiming large batches rather than mostly finding
     * nothing, either lazy expiry is not firing where expected, or there is
     * a class of seat nobody reads. Skipping the zero recordings would
     * silently answer a different question ("how big are the sweeps that
     * find something"), so do not "optimise" this away.
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void sweepExpiredHolds() {
        Instant now = Instant.now();
        List<Long> expiredSeatIds = eventSeatRepository.findIdsOfExpiredHeldSeatsForUpdate(now);
        if (!expiredSeatIds.isEmpty()) {
            eventSeatRepository.releaseExpiredHeldSeats(expiredSeatIds, now);
        }
        int expiredHoldsCount = holdRepository.expireOverdueHolds(now);
        sweepMetrics.recordSweep(expiredSeatIds.size(), expiredHoldsCount);
    }
}
