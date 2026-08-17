package com.seatvault.seat_vault.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code db} readiness indicator as a fast, decoupled
 * reachability probe instead of Boot's auto-configured pooled one - see
 * ADR-0014 for the full "why"; this class carries only what the code itself
 * needs to justify.
 *
 * <p><b>The bean must be named exactly {@code dbHealthIndicator}.</b> {@code
 * DataSourceHealthContributorAutoConfiguration} backs off on {@code
 * @ConditionalOnMissingBean(name = {"dbHealthIndicator", "dbHealthContributor"})}
 * (verified with {@code javap -v} against {@code spring-boot-jdbc} 4.1.0, not
 * assumed from Spring's docs) - that bean name is the seam, and this
 * substitutes cleanly while still resolving as {@code db} in the readiness
 * group (unchanged: {@code management.endpoint.health.group.readiness.include
 * =readinessState,db} in {@code application.properties}).
 *
 * <p><b>This is deliberately not, and must never become, a {@link
 * javax.sql.DataSource} bean.</b> {@code
 * DataSourceAutoConfiguration$PooledDataSourceConfiguration} carries {@code
 * @ConditionalOnMissingBean({DataSource.class, XADataSource.class})}, so
 * registering any {@code DataSource} bean here would silently suppress
 * Boot's auto-configured application datasource entirely - a catastrophic
 * takeover, not a health-check concern.
 *
 * <p><b>The test seam.</b> {@link DbHealthProbeTarget} defaults to being
 * derived from the always-present {@link JdbcConnectionDetails} bean (itself
 * declared inside {@code PooledDataSourceConfiguration}, so it disappears
 * too if the rule above is ever violated), via {@link
 * ObjectProvider#getIfAvailable(java.util.function.Supplier)}. A {@code
 * @TestConfiguration} can supply its own {@code DbHealthProbeTarget} bean
 * (see the test-side {@code DeadPortDbHealthProbeTargetTestConfig}) to point
 * only this health path at a dead port, leaving {@code JdbcConnectionDetails}
 * - and therefore the application's real datasource - untouched. {@code
 * ObjectProvider} resolves this lazily against the fully-registered bean
 * definition set, so it is correct regardless of the relative order in which
 * this configuration class and a test's {@code @TestConfiguration} are
 * processed; no bean-definition-override or conditional-ordering behaviour is
 * being relied on here.
 */
@Configuration(proxyBeanMethods = false)
public class DbHealthIndicatorConfig {

    @Bean
    HealthIndicator dbHealthIndicator(
            JdbcConnectionDetails connectionDetails, ObjectProvider<DbHealthProbeTarget> probeTargetProvider) {
        DbHealthProbeTarget target =
                probeTargetProvider.getIfAvailable(() -> DbHealthProbeTarget.from(connectionDetails));
        return new PostgresReachabilityHealthIndicator(target);
    }
}
