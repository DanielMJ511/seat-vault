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
 * {@code PGProperty}'s bytecode, not assumed from documentation. This
 * application's own HikariCP pool ({@code spring.datasource.*}) is untouched
 * by any of this - that decoupling is the entire point (see ADR-0014's "Why
 * not simply shorten Hikari's connection-timeout").
 *
 * <p><b>This probe has two phases, and their bounds add up: ~5s worst
 * case.</b> Measured against a frozen server ({@code docker pause}, which
 * completes the TCP handshake and then never answers). The login phase is
 * bounded by {@code loginTimeout} - 3.0s, repeatable, with
 * {@code socketTimeout} never binding there because the overall deadline
 * always fires first. The {@code SELECT 1} that follows is a separate read
 * bounded by {@code socketTimeout} alone - 2.0s, measured by freezing the
 * server in a window held open after login. {@code loginTimeout} has already
 * expired by then and does not cap it. A server that logs in slowly and then
 * stops answering pays both. A blackholed host (packets dropped at connect)
 * ends at {@code connectTimeout} instead: 2.1s, also measured.
 *
 * <p>Do not read the 3s login bound as the probe's ceiling; sizing the
 * container {@code HEALTHCHECK} against it rather than against the 5s sum is
 * the mistake that makes its margin look larger than it is.
 *
 * <p><b>Do not raise {@code loginTimeout} past 4 without re-measuring.</b>
 * pgjdbc's default {@code sslmode=prefer} attempts SSL first and, when that
 * attempt fails, <em>retries the whole connection in plaintext</em> - so a
 * hung login spends {@code socketTimeout} twice. With {@code loginTimeout}
 * disabled the same frozen server took 4.1s ({@code sslmode=disable} took
 * 2.1s, confirming the cause). Today the 3s deadline fires before that second
 * read can finish; raise it and this probe silently inherits the 4.1s path.
 *
 * <p><b>These values are coupled across files:</b> {@code loginTimeout} +
 * {@code socketTimeout} (5s) &lt; {@code HEALTHCHECK --timeout} (6s) &lt;
 * {@code --interval} (10s), the latter two in the {@code Dockerfile}. Nothing
 * enforces that ordering at build time. Break it and the container
 * healthcheck cuts the request off before this probe can return its own 503 -
 * the exact opacity ADR-0014 exists to remove.
 *
 * <p><b>What these figures do not cover: DNS.</b> Hostname resolution
 * happens before the socket is opened, and no measurement here establishes
 * that any of these three properties bound it. In a container the URL host is
 * a Docker DNS name, so a resolver hang is a plausible path past this budget.
 * Untested, and recorded as untested rather than assumed either way.
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
