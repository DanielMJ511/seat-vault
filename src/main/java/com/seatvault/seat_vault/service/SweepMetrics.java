package com.seatvault.seat_vault.service;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Owns the two {@code seatvault.sweep.*} metric names so each is written in
 * exactly one place. A misspelled metric name is silent - it yields a
 * permanently empty time series and no error anywhere - which is why {@code
 * HoldSweepService} calls this rather than reaching for a raw {@link
 * MeterRegistry} itself at the call site (T-005).
 *
 * <p>Both are {@link DistributionSummary}, not {@code Counter}. A counter
 * reading "500 seats reclaimed" cannot distinguish 500 healthy sweeps of one
 * seat each from a single 500-seat backlog spike, and that distinction is
 * the entire question this metric exists to answer (see the comment on
 * {@code HoldSweepService#sweepExpiredHolds}). A {@code DistributionSummary}
 * reports count/total/mean/max from the same recorded values, so the worst
 * batch the system has ever seen is visible via {@code max}.
 *
 * <p>Deliberately no tags: nothing here varies by seat, event, or user, so
 * the cardinality trap that governs custom metrics doesn't arise.
 */
@Component
public class SweepMetrics {

    private final DistributionSummary seatsReclaimed;
    private final DistributionSummary holdsExpired;

    public SweepMetrics(MeterRegistry meterRegistry) {
        this.seatsReclaimed = DistributionSummary.builder("seatvault.sweep.seats.reclaimed")
                .description("event_seats rows flipped HELD -> AVAILABLE per sweep run "
                        + "(recorded every run, including 0 for an idle sweep)")
                .register(meterRegistry);
        this.holdsExpired = DistributionSummary.builder("seatvault.sweep.holds.expired")
                .description("holds rows flipped ACTIVE -> EXPIRED per sweep run "
                        + "(recorded every run, including 0 for an idle sweep)")
                .register(meterRegistry);
    }

    /**
     * Records one sweep's outcome. Must be called exactly once per {@code
     * HoldSweepService#sweepExpiredHolds()} invocation, unconditionally -
     * including when both counts are 0. See the comment at that call site
     * for why the zeros are recorded rather than skipped.
     */
    public void recordSweep(long seatsReclaimedCount, long holdsExpiredCount) {
        seatsReclaimed.record(seatsReclaimedCount);
        holdsExpired.record(holdsExpiredCount);
    }
}
