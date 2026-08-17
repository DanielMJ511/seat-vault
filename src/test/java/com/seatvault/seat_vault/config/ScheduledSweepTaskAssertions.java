package com.seatvault.seat_vault.config;

import com.seatvault.seat_vault.service.HoldSweepService;
import java.lang.reflect.Field;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.util.ReflectionUtils;

/**
 * Shared observation logic for T-001's OFF/ON gate tests ({@link
 * HoldSweepSchedulingDisabledIntegrationTest} and {@link
 * HoldSweepSchedulingEnabledInDevIntegrationTest}): does the given {@link
 * ScheduledTaskHolder} have a registered task whose underlying method is
 * {@code HoldSweepService#sweepExpiredHolds}?
 *
 * <p><b>Why this has to unwrap a private field.</b> Confirmed by direct
 * observation (printing the actual runtime type from a running context, per
 * the M8 retro's "a claim about framework internals is a hypothesis until
 * observed" rule) rather than assumed from the public API: Spring 7's
 * scheduling support wraps every registered {@link Task}'s {@code Runnable}
 * in a package-private {@code Task$OutcomeTrackingRunnable} (added for
 * {@link Task#getLastExecutionOutcome()}), so {@code task.getRunnable()} is
 * <em>never</em> directly a {@link ScheduledMethodRunnable} at this Boot
 * version - it always has to be unwrapped one layer first. {@code
 * Task$OutcomeTrackingRunnable} exposes no public accessor for its delegate,
 * so the only way to reach the real {@link ScheduledMethodRunnable} - and
 * therefore the {@link java.lang.reflect.Method} actually being invoked - is
 * reflection on its private {@code runnable} field.
 */
final class ScheduledSweepTaskAssertions {

    private static final String OUTCOME_TRACKING_RUNNABLE_CLASS_NAME =
            "org.springframework.scheduling.config.Task$OutcomeTrackingRunnable";

    private ScheduledSweepTaskAssertions() {
    }

    static boolean sweepExpiredHoldsIsScheduled(ScheduledTaskHolder scheduledTaskHolder) {
        if (scheduledTaskHolder == null) {
            return false;
        }
        return scheduledTaskHolder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .map(Task::getRunnable)
                .map(ScheduledSweepTaskAssertions::unwrap)
                .filter(ScheduledMethodRunnable.class::isInstance)
                .map(ScheduledMethodRunnable.class::cast)
                .anyMatch(runnable -> runnable.getMethod().getDeclaringClass().equals(HoldSweepService.class)
                        && runnable.getMethod().getName().equals("sweepExpiredHolds"));
    }

    /**
     * Unwraps {@code Task$OutcomeTrackingRunnable} to the delegate it was
     * constructed with. Returns the input unchanged for any other runnable
     * type (including an already-unwrapped {@link ScheduledMethodRunnable}),
     * so this stays harmless if a future Spring version stops wrapping tasks.
     */
    private static Runnable unwrap(Runnable runnable) {
        if (!OUTCOME_TRACKING_RUNNABLE_CLASS_NAME.equals(runnable.getClass().getName())) {
            return runnable;
        }
        Field delegateField = ReflectionUtils.findField(runnable.getClass(), "runnable", Runnable.class);
        if (delegateField == null) {
            throw new IllegalStateException(OUTCOME_TRACKING_RUNNABLE_CLASS_NAME
                    + " no longer has a 'runnable' field - this unwrap helper needs updating for the "
                    + "current Spring version rather than silently reporting no scheduled task.");
        }
        ReflectionUtils.makeAccessible(delegateField);
        return (Runnable) ReflectionUtils.getField(delegateField, runnable);
    }
}
