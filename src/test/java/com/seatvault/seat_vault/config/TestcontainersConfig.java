package com.seatvault.seat_vault.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Wires real Postgres and Redis containers (via Testcontainers) into the
 * Spring context for integration tests, replacing the need for an
 * in-memory/no-op datasource.
 * <p>
 * Import this into any {@code @SpringBootTest} that needs both a real
 * datasource and a Redis connection. Slice tests that only need one of the
 * two should import {@link PostgresTestcontainersConfig} or
 * {@link RedisTestcontainersConfig} directly instead, so they don't pay for
 * starting a container they never use.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({PostgresTestcontainersConfig.class, RedisTestcontainersConfig.class})
public class TestcontainersConfig {
}
