package com.seatvault.seat_vault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.seatvault.seat_vault.config.HoldProperties;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Isolated unit coverage of {@link RedisLockService}'s fail-fast/fail-open
 * split, stated precisely in that class's own Javadoc: fail <em>fast</em>
 * (return {@link Optional#empty()}) on genuine contention, fail
 * <em>open</em> (return the {@link RedisLockService#REDIS_UNAVAILABLE_SENTINEL}
 * sentinel) when Redis itself throws. This is what ADR-0001 ("Postgres is the
 * concurrency authority, Redis is a non-authoritative optimization") rests
 * on at the unit level - before this class, neither the {@code tryLock}
 * catch clause nor {@code unlock}'s sentinel short-circuit had any test, and
 * that is precisely the code that runs during a real Redis outage.
 *
 * <p>Uses a Mockito mock of {@link StringRedisTemplate} configured to throw
 * {@link RedisConnectionFailureException} (a {@code DataAccessException}),
 * not a genuinely broken connection - deliberately, since the point of
 * {@link #unlockGivenSentinelTokenMakesNoRedisCall()} is proving the
 * <em>absence</em> of an interaction, which a mock's {@code
 * verifyNoInteractions} demonstrates directly. The complementary,
 * real-connection-stack proof (that a genuinely dead Redis connection
 * produces the same {@code DataAccessException} through the real Spring
 * Data Redis stack, and that {@code HoldService} behaves correctly end to
 * end when it does) lives in {@code HoldRedisUnavailableRaceIntegrationTest}
 * instead - see ADR-0001.
 */
@ExtendWith(MockitoExtension.class)
class RedisLockServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final HoldProperties holdProperties = new HoldProperties(5, 8, 10);

    private RedisLockService newService() {
        return new RedisLockService(redisTemplate, holdProperties);
    }

    @Test
    void tryLockReturnsARealTokenOnSuccessfulAcquisition() {
        RedisLockService service = newService();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:event-seat:1"), any(), eq(Duration.ofSeconds(10))))
                .thenReturn(true);

        Optional<String> token = service.tryLock(1L);

        assertThat(token).isPresent();
        assertThat(token.get()).isNotEqualTo(RedisLockService.REDIS_UNAVAILABLE_SENTINEL);
    }

    @Test
    void tryLockReturnsEmptyOnGenuineContentionRatherThanTheFailOpenSentinel() {
        RedisLockService service = newService();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:event-seat:1"), any(), any(Duration.class))).thenReturn(false);

        assertThat(service.tryLock(1L)).isEmpty();
    }

    /**
     * The core fail-open assertion: when Redis itself throws (simulating an
     * outage), {@code tryLock} does not propagate the exception and does not
     * fail fast - it returns the sentinel so {@code HoldService} can fall
     * through to the Postgres row lock instead (ADR-0001).
     */
    @Test
    void tryLockReturnsTheSentinelWhenRedisThrowsADataAccessException() {
        RedisLockService service = newService();
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("connection refused"));

        Optional<String> token = service.tryLock(1L);

        assertThat(token).contains(RedisLockService.REDIS_UNAVAILABLE_SENTINEL);
    }

    /**
     * The other half of the fail-open contract: {@code unlock} given the
     * sentinel token must not make any call on the template at all -
     * {@code HoldService}'s {@code finally} block always calls {@code
     * unlock} for every lock it acquired (including sentinel "locks"), and a
     * second doomed Redis round trip per seat would be pure waste during an
     * outage that has already been established. Verified with {@code
     * verifyNoInteractions}, which proves the absence of the call directly
     * rather than inferring it from a lack of thrown exception.
     */
    @Test
    void unlockGivenSentinelTokenMakesNoRedisCall() {
        RedisLockService service = newService();

        service.unlock(1L, RedisLockService.REDIS_UNAVAILABLE_SENTINEL);

        verifyNoInteractions(redisTemplate);
    }

    /**
     * Contrast case for the test above: proves {@code verifyNoInteractions}
     * there isn't vacuously true because {@code unlock} never calls the
     * template at all - with a real (non-sentinel) token, it does.
     */
    @Test
    void unlockGivenARealTokenDoesCallRedis() {
        RedisLockService service = newService();

        service.unlock(1L, "real-token");

        verify(redisTemplate)
                .execute(any(RedisScript.class), eq(Collections.singletonList("lock:event-seat:1")), eq("real-token"));
    }

    @Test
    void tryLockOnDataAccessExceptionNeverCallsSetIfAbsentAgain() {
        RedisLockService service = newService();
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("connection refused"));

        service.tryLock(1L);

        verify(valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));
    }

    /**
     * {@code unlock}'s own catch clause is the same fail-open shape as
     * {@code tryLock}'s, for a real (non-sentinel) token whose Redis call
     * itself fails (e.g. the lock was acquired successfully but Redis went
     * down before release) - it must swallow the exception rather than
     * propagate it, since {@code HoldService}'s {@code finally} block cannot
     * usefully react to a release failure and the TTL will reclaim the key
     * anyway.
     */
    @Test
    void unlockSwallowsADataAccessExceptionOnARealTokenRatherThanPropagatingIt() {
        RedisLockService service = newService();
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatCode(() -> service.unlock(1L, "real-token")).doesNotThrowAnyException();
    }
}
