package com.seatvault.seat_vault.dto;

import java.time.Instant;

public record AuthResponse(String token, Instant expiresAt) {
}
