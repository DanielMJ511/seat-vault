package com.seatvault.seat_vault.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Reachability probe for Postgres, replacing Boot's auto-configured pooled
 * {@code db} indicator (see ADR-0014 for the full reasoning; this class
 * intentionally does not restate it at length).
 *
 * <p><b>Fresh {@link DriverManager} connection per probe, never pooled.</b> A
 * cached idle connection could answer {@code UP} at a moment when no *new*
 * connection can be established, which is exactly the failure this probe
 * exists to catch. One TCP connect every ten seconds is not a cost worth
 * optimising against that correctness gap.
 *
 * <p><b>Runs {@code SELECT 1}, not connect-and-close.</b> A server that
 * completes TCP and authentication but cannot serve queries (in recovery, or
 * out of WAL disk) must still report {@code DOWN}; connect-only would report
 * it {@code UP}, making this replacement strictly weaker than the indicator
 * it replaces.
 *
 * <p><b>Timeouts are pgjdbc connection properties, not Hikari's.</b> {@code
 * connectTimeout}/{@code socketTimeout}/{@code loginTimeout} are parsed by
 * pgjdbc with {@code Integer.parseInt} - whole seconds only, verified against
 * {@code PGProperty}'s bytecode, not assumed from documentation. The values
 * below put the worst case (on a blackholed, not merely refused, host) near
 * five seconds, which is why the container {@code HEALTHCHECK}'s own {@code
 * --timeout} widened from 3s to 6s alongside this change. This application's
 * own HikariCP pool ({@code spring.datasource.*}) is untouched by any of
 * this - that decoupling is the entire point (see ADR-0014's "Why not simply
 * shorten Hikari's connection-timeout").
 */
class PostgresReachabilityHealthIndicator extends AbstractHealthIndicator {

    private static final String CONNECT_TIMEOUT_SECONDS = "2";
    private static final String SOCKET_TIMEOUT_SECONDS = "2";
    private static final String LOGIN_TIMEOUT_SECONDS = "3";

    private final DbHealthProbeTarget target;

    PostgresReachabilityHealthIndicator(DbHealthProbeTarget target) {
        this.target = target;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", target.username());
        connectionProperties.setProperty("password", target.password());
        connectionProperties.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
        connectionProperties.setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
        connectionProperties.setProperty("loginTimeout", LOGIN_TIMEOUT_SECONDS);

        // Any SQLException here (refused connection, blackholed-host
        // timeout, or the query itself failing) propagates out of
        // doHealthCheck and is turned into Health.down(exception) by
        // AbstractHealthIndicator#health() - verified against its bytecode
        // rather than assumed, so no catch clause is needed here.
        try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), connectionProperties);
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }
        builder.up();
    }
}
