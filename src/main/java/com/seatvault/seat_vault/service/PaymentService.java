package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.entity.Booking;
import com.seatvault.seat_vault.entity.PaymentStatus;
import java.math.BigDecimal;

/**
 * Charges a Booking for the given amount. A real domain concept (CONTEXT.md:
 * a Booking must be paid for before it's confirmed) independent of whether the
 * underlying provider is simulated or real. Implementations must return only
 * {@link PaymentStatus#SUCCEEDED} or {@link PaymentStatus#FAILED} - never
 * {@code PENDING}, since this call is synchronous.
 */
public interface PaymentService {

    PaymentStatus charge(Booking booking, BigDecimal amount);
}
