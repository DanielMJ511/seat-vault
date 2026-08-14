package com.seatvault.seat_vault.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Standard API error payload returned for every handled exception. {@code
 * code} is always one of the closed set of constants in {@link
 * com.seatvault.seat_vault.exception.ErrorCode}.
 *
 * @param timestamp moment the error was produced
 * @param status    HTTP status code
 * @param code      stable machine-readable error code (e.g. {@code SEAT_ALREADY_HELD})
 * @param message   human-readable description of the error
 * @param path      request path that triggered the error
 */
public record ErrorResponse(
        @Schema(example = "2026-01-01T00:00:00Z") Instant timestamp,
        @Schema(example = "404") int status,
        @Schema(example = "VENUE_NOT_FOUND") String code,
        @Schema(example = "No venue exists with the given id.") String message,
        @Schema(example = "/api/venues/999999") String path
) {
}
