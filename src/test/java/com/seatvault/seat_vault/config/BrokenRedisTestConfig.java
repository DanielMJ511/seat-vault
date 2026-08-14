package com.seatvault.seat_vault.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Supplies a {@link RedisConnectionFactory} pointed at a dead local port -
 * nothing ever listens on it - instead of a real Testcontainers Redis, so
 * every Redis operation genuinely fails with a {@code DataAccessException}
 * through the real Spring Data Redis / Lettuce stack: exactly what {@link
 * com.seatvault.seat_vault.service.RedisLockService#tryLock}'s catch clause
 * has to handle during a real outage (see ADR-0001 - Postgres, not Redis, is
 * the concurrency authority, and this configuration is what makes that claim
 * falsifiable).
 *
 * <p>Import this alongside {@link PostgresTestcontainersConfig} (never
 * {@link RedisTestcontainersConfig} / {@link TestcontainersConfig}) in a test
 * class that needs Postgres real and Redis broken. Because this is a
 * distinct set of {@code @TestConfiguration} classes from every other
 * {@code @SpringBootTest} in the suite, Spring's context cache cannot reuse
 * an existing context for it - the test class that imports this pays for a
 * brand-new application context (including its own, separate Postgres
 * container) on top of the connection-refused round trips below. That is a
 * deliberate, accepted cost: stopping the shared Testcontainers Redis
 * container instead was considered and rejected, since that container is
 * shared across the cached context used by every other integration test in
 * the suite - stopping it would break all of them, not just the one class
 * that wants Redis down.
 *
 * <p>A short connect timeout (well under Lettuce's default) keeps every
 * failed connection attempt fast rather than paying a longer default
 * timeout repeatedly across many racing threads and seats.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BrokenRedisTestConfig {

    /** Nothing listens here - any real listener would defeat the point of this class. */
    private static final int DEAD_PORT = 18734;

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(200);

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration("127.0.0.1", DEAD_PORT);

        ClientOptions clientOptions = ClientOptions.builder()
                .autoReconnect(false)
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build())
                .build();
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(CONNECT_TIMEOUT)
                .shutdownTimeout(Duration.ZERO)
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(serverConfig, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }
}
