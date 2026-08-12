package com.seatvault.seat_vault.dto;

import java.time.Instant;

public record EventResponse(Long id, String name, Instant startsAt, Long venueId, String venueName) {
}
