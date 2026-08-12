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

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void sweepExpiredHolds() {
        Instant now = Instant.now();
        eventSeatRepository.releaseExpiredHeldSeats(now);
        holdRepository.expireOverdueHolds(now);
    }
}
