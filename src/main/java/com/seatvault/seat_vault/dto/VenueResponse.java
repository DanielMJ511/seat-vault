package com.seatvault.seat_vault.dto;

import java.time.Instant;

public record VenueResponse(Long id, String name, String address, Instant createdAt) {
}
