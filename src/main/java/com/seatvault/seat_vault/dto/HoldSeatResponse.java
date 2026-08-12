package com.seatvault.seat_vault.dto;

import java.math.BigDecimal;

public record HoldSeatResponse(
        Long eventSeatId, String section, String rowLabel, Integer seatNumber, BigDecimal priceSnapshot) {
}
