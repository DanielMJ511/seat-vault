package com.seatvault.seat_vault.config;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * The connection target {@link PostgresReachabilityHealthIndicator} probes -
 * deliberately not the application's {@link JdbcConnectionDetails} bean
 * itself. This is the seam ADR-0014 calls for: a {@code @TestConfiguration}
 * can supply a replacement bean of this small type, pointed at a dead port,
 * without touching {@code JdbcConnectionDetails}. That distinction matters
 * because {@code JdbcConnectionDetails} is also what the application's real
 * datasource is built from (see {@code DataSourceAutoConfiguration
 * $PooledDataSourceConfiguration}); overriding it directly in a test would
 * redirect the application datasource too and the context would not boot -
 * the wall M8 concluded made "Postgres down" untestable in-process (see
 * ADR-0013, amended by ADR-0014).
 *
 * <p>Production wiring (see {@link DbHealthIndicatorConfig}) derives this
 * from {@link JdbcConnectionDetails} - never from {@code spring.datasource.*}
 * properties directly, since those do not carry the Testcontainers container
 * URL under {@code @ServiceConnection}.
 */
public record DbHealthProbeTarget(String jdbcUrl, String username, String password) {

    static DbHealthProbeTarget from(JdbcConnectionDetails connectionDetails) {
        return new DbHealthProbeTarget(
                connectionDetails.getJdbcUrl(), connectionDetails.getUsername(), connectionDetails.getPassword());
    }
}
