package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.entity.Booking;
import com.seatvault.seat_vault.entity.PaymentStatus;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * A simulated payment provider: always approves unless a test has forced a
 * different outcome via {@link #forceNextOutcome(PaymentStatus)}. The forced
 * outcome persists across calls until {@link #resetForcedOutcome()} - there's
 * no real provider behind this to model a one-shot response from, and tests
 * need a stable outcome across a whole scenario (including concurrent confirm
 * attempts within one test), not just the next call.
 *
 * <p>Test-only hooks are deliberately kept off the {@link PaymentService}
 * interface and exposed only on this concrete type, so integration tests
 * autowire {@code SimulatedPaymentServiceImpl} directly rather than widening
 * the production-facing contract.
 */
@Service
public class SimulatedPaymentServiceImpl implements PaymentService {

    private final AtomicReference<PaymentStatus> forcedOutcome = new AtomicReference<>();
    private final AtomicInteger invocationCount = new AtomicInteger();

    @Override
    public PaymentStatus charge(Booking booking, BigDecimal amount) {
        invocationCount.incrementAndGet();
        PaymentStatus forced = forcedOutcome.get();
        return forced != null ? forced : PaymentStatus.SUCCEEDED;
    }

    public void forceNextOutcome(PaymentStatus outcome) {
        forcedOutcome.set(outcome);
    }

    public void resetForcedOutcome() {
        forcedOutcome.set(null);
    }

    public int invocationCount() {
        return invocationCount.get();
    }

    public void resetInvocationCount() {
        invocationCount.set(0);
    }
}
