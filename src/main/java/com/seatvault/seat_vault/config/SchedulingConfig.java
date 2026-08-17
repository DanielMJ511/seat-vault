package com.seatvault.seat_vault.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's {@code @Scheduled} machinery - which is what actually
 * starts {@code HoldSweepService#sweepExpiredHolds}'s 30-second timer - gated
 * behind {@code seatvault.sweep.scheduling.enabled} (default {@code true} via
 * {@code matchIfMissing}, but also written out explicitly in {@code
 * application.properties} so there is no invisible default to reason about).
 *
 * <p><b>Why this exists at all.</b> {@code @EnableScheduling} used to sit
 * directly on {@code SeatVaultApplication} with no gate, so every Spring
 * context any test started ran a real sweep tick every 30 seconds for as
 * long as the JVM lived - and because Spring caches contexts across test
 * classes, that could mean the whole suite's runtime. Several integration
 * tests are deliberately not {@code @Transactional} and commit real
 * multi-seat holds with the production 5-minute TTL; a slower run, a bigger
 * suite, or one added {@code Thread.sleep} is all it takes for a background
 * tick to reclaim one of those holds mid-assertion, and the failure would
 * present as an unrelated test flaking intermittently - the hardest kind to
 * diagnose. See {@code loop/tasks/T-001.md} and the M8 retro in {@code
 * loop/LESSONS.md} for how close this came to biting T-005's metrics test.
 *
 * <p><b>Why a property, not {@code @Profile("!test")}.</b> A profile name
 * ties production reconciliation to a string that a real deployed
 * environment could easily also be running under (e.g. a staging box started
 * with {@code SPRING_PROFILES_ACTIVE=test}), which would silently kill the
 * sweep with no signal - a far worse outcome than the test flakiness this
 * class fixes. A property that defaults to enabled means disabling
 * reconciliation always requires an explicit, greppable act, and it puts the
 * gate ({@code application-test.properties}) where a test author actually
 * looks.
 *
 * <p><b>Why this gates scheduling and not the {@code HoldSweepService} bean
 * itself.</b> Per ADR-0002 the sweep is UX-only reconciliation - lazy
 * check-on-read is what's actually correctness-critical - so disabling the
 * timer in tests costs no coverage of correctness. But several tests
 * (see {@code HoldIntegrationTest}, {@code HoldSweepSeatLockOrderIntegrationTest})
 * inject {@code HoldSweepService} directly and call {@code
 * sweepExpiredHolds()} themselves to test the sweep's own behaviour
 * deterministically, without waiting on a timer. Conditionalizing the {@code
 * @Component} instead of the scheduling annotation would break every one of
 * them. <b>Do not widen this gate to cover the bean - only the timer that
 * invokes it should ever be conditional.</b>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "seatvault.sweep.scheduling.enabled", matchIfMissing = true)
public class SchedulingConfig {
}
