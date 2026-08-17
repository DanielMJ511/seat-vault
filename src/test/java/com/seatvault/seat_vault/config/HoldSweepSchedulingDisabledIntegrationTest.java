package com.seatvault.seat_vault.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves the OFF half of T-001's gate by observation, not by reading the
 * annotation: under the {@code test} profile ({@code
 * seatvault.sweep.scheduling.enabled=false}, see {@code
 * application-test.properties}), no scheduled task targeting {@code
 * HoldSweepService#sweepExpiredHolds} is registered in the context at all.
 *
 * <p>{@code sweepExpiredHolds} is, as of this test, the <em>only</em> {@code
 * @Scheduled} method in {@code main} - so "the context registers no scheduled
 * tasks whatsoever" is equivalent to "the sweep specifically is not
 * registered" today. This asserts on the sweep by name anyway (see {@link
 * ScheduledSweepTaskAssertions}, which walks each registered task down to its
 * underlying {@link java.lang.reflect.Method}), so the assertion keeps its
 * meaning if a second {@code @Scheduled} method is ever added elsewhere and
 * left enabled.
 *
 * <p>Because {@link SchedulingConfig} is entirely skipped when the property
 * is {@code false} (its {@code @EnableScheduling} import never runs), Spring
 * never registers the {@code internalScheduledAnnotationProcessor} bean, so
 * there may be no {@link ScheduledTaskHolder} bean in the context at all. That
 * is itself a valid way to observe "nothing is scheduled" and is treated as
 * such below (via {@code @Autowired(required = false)}) rather than as an
 * error.
 *
 * <p>Shares {@code HoldIntegrationTest}'s exact {@code @SpringBootTest
 * @AutoConfigureMockMvc @Import(TestcontainersConfig.class)
 * @ActiveProfiles("test") @TestPropertySource(...seed)} annotation set on
 * purpose, so this class joins that same cached Spring context (and its
 * already-paid-for Postgres/Redis containers) instead of starting a new one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class HoldSweepSchedulingDisabledIntegrationTest {

    @Autowired(required = false)
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    void noScheduledTaskTargetsHoldSweepServiceSweepExpiredHolds() {
        assertThat(ScheduledSweepTaskAssertions.sweepExpiredHoldsIsScheduled(scheduledTaskHolder))
                .as("seatvault.sweep.scheduling.enabled=false (application-test.properties) must stop "
                        + "HoldSweepService#sweepExpiredHolds from being registered as a scheduled task, "
                        + "not merely leave it unused")
                .isFalse();
    }
}
