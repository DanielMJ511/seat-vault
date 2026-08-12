package com.seatvault.seat_vault.dto;

import com.seatvault.seat_vault.entity.EventSeatStatus;
import java.math.BigDecimal;

public record EventSeatResponse(
        Long id, String section, String rowLabel, Integer seatNumber, BigDecimal price, EventSeatStatus status) {
}
