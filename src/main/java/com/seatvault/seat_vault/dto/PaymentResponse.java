package com.seatvault.seat_vault.dto;

import com.seatvault.seat_vault.entity.PaymentStatus;
import java.math.BigDecimal;

public record PaymentResponse(Long id, PaymentStatus status, BigDecimal amount) {
}
