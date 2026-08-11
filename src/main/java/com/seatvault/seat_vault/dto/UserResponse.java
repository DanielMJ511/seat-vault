package com.seatvault.seat_vault.dto;

import java.time.Instant;

public record UserResponse(Long id, String email, Instant createdAt) {
}
