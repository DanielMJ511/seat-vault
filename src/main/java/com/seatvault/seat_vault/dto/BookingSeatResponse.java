package com.seatvault.seat_vault.dto;

import java.math.BigDecimal;

public record BookingSeatResponse(
        Long eventSeatId, String section, String rowLabel, Integer seatNumber, BigDecimal priceSnapshot) {
}
