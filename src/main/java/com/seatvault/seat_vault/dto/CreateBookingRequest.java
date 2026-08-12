package com.seatvault.seat_vault.dto;

import jakarta.validation.constraints.NotNull;

public record CreateBookingRequest(@NotNull Long holdId) {
}
