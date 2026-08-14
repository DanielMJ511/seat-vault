package com.seatvault.seat_vault.exception;

import org.springframework.http.HttpStatus;

/**
 * The closed set of machine-readable error codes the API can ever return.
 * Every code is paired here with the HTTP status it always carries and a
 * human-readable description - a single source of truth so a code can never
 * drift to a different status depending on where it's thrown, and a typo in a
 * call site is a compile error rather than a silently-invented new code. See
 * {@code docs/adr/0009-error-codes-are-a-closed-enum-and-split-on-client-action.md}
 * for the design record, including the rule for when two situations get
 * distinct codes (only when the client's next action differs) versus merged
 * ones ({@link #SEAT_ALREADY_HELD}) or split ones ({@link #HOLD_EXPIRED} out
 * of {@link #HOLD_NOT_ACTIVE}).
 *
 * <p>{@code description} feeds T-005's generated OpenAPI {@code @ApiResponse}
 * examples, so it is written for an external API consumer, not just this
 * codebase's own contributors.
 */
public enum ErrorCode {

    // --- Auth ---
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "An account with this email address already exists."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "The supplied email or password is incorrect."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "The authenticated user's account no longer exists."),

    // --- Catalog (Venue / Event / EventSeat) ---
    VENUE_NOT_FOUND(HttpStatus.NOT_FOUND, "No venue exists with the given id."),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "No event exists with the given id."),
    EVENT_SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "No event seat exists with the given id."),

    // --- Hold ---
    EMPTY_SEAT_LIST(HttpStatus.BAD_REQUEST, "At least one seat must be requested."),
    TOO_MANY_SEATS(HttpStatus.BAD_REQUEST, "A hold may not cover more than the configured maximum number of seats."),
    MULTIPLE_EVENTS_IN_HOLD(HttpStatus.BAD_REQUEST, "All seats in a single hold request must belong to the same event."),
    SEAT_ALREADY_HELD(HttpStatus.CONFLICT,
            "The seat is currently held by another user - retry shortly. Returned identically whether contention "
                    + "was detected by the fail-fast Redis lock or the authoritative Postgres check, since the "
                    + "client's correct action is the same either way."),
    SEAT_ALREADY_BOOKED(HttpStatus.CONFLICT, "The seat has already been booked for this event; choose another seat."),
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND,
            "No hold exists with the given id, or it belongs to a different user - the two are not distinguished."),
    HOLD_NOT_ACTIVE(HttpStatus.CONFLICT,
            "The hold is not usable for this action because it has already been converted into a booking (or, for "
                    + "a defensive check, was never a normal hold in the first place). It has not expired - see "
                    + "HOLD_EXPIRED for that case."),
    HOLD_EXPIRED(HttpStatus.CONFLICT,
            "The hold's countdown ran out - or it was manually released, which is stored identically per ADR-0007 "
                    + "- before this action completed. Start a new hold."),

    // --- Booking / Payment ---
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND,
            "No booking exists with the given id, or it belongs to a different user - the two are not "
                    + "distinguished (ADR-0008)."),
    BOOKING_NOT_CONFIRMED(HttpStatus.CONFLICT, "The booking is not in a CONFIRMED state and cannot be cancelled."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "No payment record exists for this booking."),

    // --- Request-shape / infrastructure (thrown by GlobalExceptionHandler itself) ---
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "The request body failed bean-validation constraints."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "A path or query parameter could not be converted to its expected type."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred."),

    // --- Security layer (written directly by SecurityConfig; never reaches GlobalExceptionHandler) ---
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required to access this resource."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN,
            "The caller is authenticated but lacks permission for this resource. Unreachable today - no "
                    + "authorization rule uses hasRole/hasAuthority yet - kept wired up ahead of future "
                    + "role-based endpoints needing it.");

    private final HttpStatus status;
    private final String description;

    ErrorCode(HttpStatus status, String description) {
        this.status = status;
        this.description = description;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }
}
