package com.seatvault.seat_vault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code hold.*} configuration: how long a seat hold lasts before it's
 * eligible for lazy-expiry/sweep reclamation ({@link #ttlMinutes()}), how many
 * seats a single hold may cover ({@link #maxSeatsPerHold()}), and how long the
 * short-lived Redis contention lock is held for ({@link #redisLockTtlSeconds()}
 * — independent of {@link #ttlMinutes()}, which governs the seat hold itself).
 * All three are env-overridable (see {@code application.properties}).
 */
@ConfigurationProperties(prefix = "hold")
public record HoldProperties(long ttlMinutes, int maxSeatsPerHold, long redisLockTtlSeconds) {
}
