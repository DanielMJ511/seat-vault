package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.HoldRepository;
import java.time.Instant;
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
     * The order of these two statements is load-bearing, not stylistic. They
     * run in one transaction and match the same expired holds by construction
     * (same {@code now}, complementary predicates), so between them this
     * method locks both an {@code event_seats} row and that seat's {@code
     * holds} row - and it must take them in the order every other path takes
     * them: <b>seats first, holds second</b> (ADR-0011). Swapping these lines,
     * or splitting them into two transactions in a way that reverses their
     * effective order, re-creates the ABBA deadlock T-007 fixed - this method
     * being on a 30-second timer, it would fire unattended in production
     * rather than needing a user to trigger it.
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void sweepExpiredHolds() {
        Instant now = Instant.now();
        eventSeatRepository.releaseExpiredHeldSeats(now);
        holdRepository.expireOverdueHolds(now);
    }
}
