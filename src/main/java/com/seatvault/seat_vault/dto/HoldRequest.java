package com.seatvault.seat_vault.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record HoldRequest(@NotEmpty List<Long> eventSeatIds) {
}
