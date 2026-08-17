package com.seatvault.seat_vault.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the ON half of T-001's gate by observation: with {@code
 * seatvault.sweep.scheduling.enabled} left entirely <em>absent</em>, a real
 * scheduled task targeting {@code HoldSweepService#sweepExpiredHolds} really
 * is registered (see {@link ScheduledSweepTaskAssertions}). This half is not
 * optional - a gate that only ever proved OFF could just as easily be a gate
 * that silently disabled production reconciliation too, which per {@link
 * SchedulingConfig}'s Javadoc is a far worse outcome than the flakiness this
 * task fixes, and only asserting in both directions can tell the two apart.
 *
 * <p><b>Why {@code @ActiveProfiles("dev")} and not "no profile".</b> {@code
 * JwtProperties#validate} ({@code security/JwtProperties.java}) throws unless
 * the {@code dev} or {@code test} profile is active, so a profile-less
 * context cannot boot here at all. {@code application-dev.properties} does
 * not set {@code seatvault.sweep.scheduling.enabled}, so a context started
 * under {@code dev} falls through to the base {@code application.properties}
 * value ({@code seatvault.sweep.scheduling.enabled=true}) - meaning this test
 * exercises the actual production default, not a fixture value written to
 * make the test pass.
 *
 * <p><b>This context runs a live, real 30-second scheduler - the exact thing
 * T-001 removes elsewhere - and that is deliberate and safe here.</b> It is
 * safe only because this context is genuinely isolated:
 *
 * <ul>
 *   <li>Its {@code @ActiveProfiles("dev")} plus {@code
 *       @Import(PostgresTestcontainersConfig.class)} give it a distinct
 *       {@code MergedContextConfiguration}, so Spring gives it its own
 *       application context rather than reusing the {@code test}-profile
 *       context every other integration test shares.</li>
 *   <li>{@code PostgresTestcontainersConfig} declares its container as a
 *       plain {@code @Bean} with no Testcontainers reuse, so a distinct
 *       context means a distinct Postgres container too - nothing this
 *       context's sweep can touch is visible to any other test.</li>
 *   <li>No other test class shares this annotation set, this class commits no
 *       fixtures for the sweep to act on, and the only thing asserted is task
 *       <em>registration</em> - never a row, a metric, or any other value the
 *       live tick could race.</li>
 * </ul>
 *
 * <p><b>What would break this argument - do not copy this pattern
 * carelessly.</b> Adding {@code @AutoConfigureMockMvc} (or any other
 * annotation that changes the merged context configuration to one shared by
 * another class) would merge this context's cache key with a sibling's,
 * handing that sibling a live 30-second timer it was never designed to
 * tolerate - reintroducing exactly the hazard this task removes. Any future
 * addition to this class must preserve strict isolation: no shared fixtures,
 * no committed state another test could observe, no annotation that widens
 * the cache key to match an existing class.
 *
 * <p>Rejected: {@code ApplicationContextRunner}: it would only prove the
 * {@code @ConditionalOnProperty} condition evaluated, not that a task
 * actually got registered with Spring's scheduler - condition-reading wearing
 * a test's clothes. Rejected: shutting the scheduler down after this class
 * runs - unnecessary machinery, since the isolation above already contains
 * it for the rest of the JVM's life.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(PostgresTestcontainersConfig.class)
class HoldSweepSchedulingEnabledInDevIntegrationTest {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    void sweepExpiredHoldsIsRegisteredAsAScheduledTaskWhenThePropertyIsAbsent() {
        assertThat(ScheduledSweepTaskAssertions.sweepExpiredHoldsIsScheduled(scheduledTaskHolder))
                .as("with seatvault.sweep.scheduling.enabled absent, the dev/production default "
                        + "(application.properties: true) must register HoldSweepService#sweepExpiredHolds "
                        + "as a live scheduled task - a gate that only ever proves OFF could just as easily "
                        + "be silently disabling production reconciliation")
                .isTrue();
    }
}
