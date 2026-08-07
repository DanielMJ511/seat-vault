package com.seatvault.seat_vault.dto;

import java.time.Instant;

/**
 * Standard API error payload returned for every handled exception.
 *
 * @param timestamp moment the error was produced
 * @param status    HTTP status code
 * @param code      stable machine-readable error code (e.g. {@code SEAT_ALREADY_RESERVED})
 * @param message   human-readable description of the error
 * @param path      request path that triggered the error
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path
) {
}
