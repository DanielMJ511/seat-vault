package com.seatvault.seat_vault.dto;

import com.seatvault.seat_vault.entity.HoldStatus;
import java.time.Instant;
import java.util.List;

public record HoldResponse(Long id, HoldStatus status, Instant expiresAt, List<HoldSeatResponse> seats) {
}
