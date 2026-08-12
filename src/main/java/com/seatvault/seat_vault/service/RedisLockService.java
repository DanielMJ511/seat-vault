package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.config.HoldProperties;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Thin, non-authoritative locking layer implementing ADR-0001's Redis
 * contract exactly: fail <em>fast</em> on genuine contention (an already-held
 * key), fail <em>open</em> on Redis unavailability. Postgres's
 * {@code SELECT ... FOR UPDATE} (see
 * {@link com.seatvault.seat_vault.repository.EventSeatRepository#findByIdForUpdate})
 * remains the actual correctness mechanism regardless of what happens here —
 * this class only exists to reject losing requests quickly under real
 * contention, without ever making Redis a single point of failure.
 */
@Service
@RequiredArgsConstructor
public class RedisLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisLockService.class);

    private static final String KEY_PREFIX = "lock:event-seat:";

    /**
     * Sentinel token returned by {@link #tryLock(Long)} when Redis itself is
     * unavailable, so hold creation can proceed and fall through to the
     * Postgres row lock instead. {@link #unlock(Long, String)} recognizes
     * this token and no-ops rather than making a doomed second call to Redis.
     */
    static final String REDIS_UNAVAILABLE_SENTINEL = "REDIS_UNAVAILABLE";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) "
                    + "else "
                    + "return 0 "
                    + "end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final HoldProperties holdProperties;

    /**
     * Attempts to acquire the short-lived contention lock for one
     * {@code EventSeat}. Returns the caller's unique unlock token on success
     * (including the fail-open sentinel case), or {@link Optional#empty()}
     * only when another caller genuinely holds the lock right now.
     */
    public Optional<String> tryLock(Long eventSeatId) {
        String key = lockKey(eventSeatId);
        String token = UUID.randomUUID().toString();
        try {
            Duration ttl = Duration.ofSeconds(holdProperties.redisLockTtlSeconds());
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable while acquiring lock for key [{}]; failing open per ADR-0001.", key, ex);
            return Optional.of(REDIS_UNAVAILABLE_SENTINEL);
        }
    }

    /**
     * Releases a lock previously acquired via {@link #tryLock(Long)}, only if
     * {@code token} still matches what's stored (a Lua CAS-delete, so this
     * caller never accidentally releases a lock some other holder has since
     * acquired after this one's TTL expired).
     */
    public void unlock(Long eventSeatId, String token) {
        if (REDIS_UNAVAILABLE_SENTINEL.equals(token)) {
            return;
        }

        String key = lockKey(eventSeatId);
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable while releasing lock for key [{}]; ignoring (TTL will expire it).", key, ex);
        }
    }

    private String lockKey(Long eventSeatId) {
        return KEY_PREFIX + eventSeatId;
    }
}
