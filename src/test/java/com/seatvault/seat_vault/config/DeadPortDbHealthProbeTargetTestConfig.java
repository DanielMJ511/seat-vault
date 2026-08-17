package com.seatvault.seat_vault.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies a {@link DbHealthProbeTarget} pointed at a dead local port -
 * nothing ever listens on it - instead of one derived from the real {@link
 * org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails} bean, so
 * {@code dbHealthIndicator} genuinely fails to connect while the
 * application's own datasource (and Flyway/JPA, which run against it at
 * context startup) stays on the real Testcontainers Postgres, untouched.
 * This is the seam ADR-0014 describes: the indicator is decoupled from
 * {@code JdbcConnectionDetails} specifically so a test can replace *only*
 * the health path without redirecting the application datasource and
 * breaking context startup - the wall M8 hit and concluded was untestable
 * (see ADR-0013, amended by ADR-0014).
 *
 * <p>Mirrors {@link BrokenRedisTestConfig}'s shape: import this alongside
 * {@link PostgresTestcontainersConfig} in a test class that needs Postgres
 * real and the {@code db} readiness indicator broken. Because this is a
 * distinct set of {@code @TestConfiguration} classes from every other
 * {@code @SpringBootTest} in the suite, Spring's context cache cannot reuse
 * an existing context for it - the test class that imports this pays for a
 * brand-new application context (including its own, separate Postgres
 * container).
 *
 * <p><b>What a dead port does and does not prove.</b> A refused connection
 * fails instantly, regardless of {@code connectTimeout}/{@code
 * socketTimeout}/{@code loginTimeout} - so a test built on this proves the
 * fail-fast mechanism and the decoupling from the application datasource,
 * never the timeout values themselves. Those only bite on a blackholed host,
 * and a blackhole test against a reserved non-routable address was
 * deliberately rejected in ADR-0014 as an environment-sensitive flake trade
 * (whether such an address drops or refuses depends on the host routing
 * table, the Docker bridge, and the CI provider's egress).
 */
@TestConfiguration(proxyBeanMethods = false)
public class DeadPortDbHealthProbeTargetTestConfig {

    /** Nothing listens here - any real listener would defeat the point of this class. */
    private static final int DEAD_PORT = 18799;

    @Bean
    DbHealthProbeTarget dbHealthProbeTarget() {
        return new DbHealthProbeTarget(
                "jdbc:postgresql://127.0.0.1:" + DEAD_PORT + "/seatvault", "seatvault", "seatvault");
    }
}
