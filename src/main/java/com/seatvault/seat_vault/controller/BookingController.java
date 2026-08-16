package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.BookingResponse;
import com.seatvault.seat_vault.dto.CreateBookingRequest;
import com.seatvault.seat_vault.dto.ErrorResponse;
import com.seatvault.seat_vault.security.AuthenticatedUser;
import com.seatvault.seat_vault.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-scoped throughout: every response here depends on which user is
 * asking, so every operation is documented as {@code bearerAuth} per
 * ADR-0004, and {@code SecurityConfig} enforces it for all five operations.
 * <p>
 * Since #14 that enforcement needs nothing from this class: the filter chain
 * denies by default, so these five are authenticated because they are not on
 * its short list of public routes, not because anyone remembered to protect
 * them. Historically they did need remembering, and M6 shipped
 * {@code GET /api/bookings/me} and {@code GET /api/bookings/{id}} without it
 * - they matched a blanket {@code GET /api/**} permitAll rule and were
 * served anonymously until T-008 (#12). {@code BookingIntegrationTest} keeps
 * the per-route regression tests for that.
 */
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    // ErrorCode.VALIDATION_ERROR (400), HOLD_NOT_FOUND / EVENT_SEAT_NOT_FOUND (404),
    // HOLD_EXPIRED / HOLD_NOT_ACTIVE (409)
    @Operation(summary = "Convert an active hold into a PENDING booking with a PENDING payment. "
            + "Seats move from HELD to BOOKED; the hold moves to CONVERTED.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Booking (and its payment) created, both PENDING.",
            content = @Content(schema = @Schema(implementation = BookingResponse.class)))
    @ApiResponse(responseCode = "400", description = "Request body failed validation (missing holdId).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "VALIDATION_ERROR", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"VALIDATION_ERROR",\
                            "message":"holdId: must not be null","path":"/api/bookings"}""")))
    @ApiResponse(responseCode = "404",
            description = "No hold exists with the given id, or it belongs to a different user - the two are "
                    + "not distinguished (HOLD_NOT_FOUND). Can also surface EVENT_SEAT_NOT_FOUND defensively.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "HOLD_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"HOLD_NOT_FOUND",\
                            "message":"Hold not found.","path":"/api/bookings"}""")))
    @ApiResponse(responseCode = "409",
            description = "The hold has expired - start a new hold (HOLD_EXPIRED); or it isn't usable for this "
                    + "action, e.g. already converted or has no seats (HOLD_NOT_ACTIVE).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "HOLD_EXPIRED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":409,"code":"HOLD_EXPIRED",\
                            "message":"Hold has expired.","path":"/api/bookings"}""")))
    @ApiResponse(responseCode = "401", description = "Authentication is required to access this resource.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "UNAUTHENTICATED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"UNAUTHENTICATED",\
                            "message":"Authentication is required to access this resource.","path":"/api/bookings"}""")))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody CreateBookingRequest request) {
        return bookingService.createFromHold(principal.id(), request);
    }

    // ErrorCode.BOOKING_NOT_FOUND / PAYMENT_NOT_FOUND (404, ADR-0008: 404 not 403 on ownership mismatch)
    @Operation(summary = "Charge the booking's payment. A repeat call on an already-decided booking "
            + "is treated as an idempotent retry and simply returns the current state without recharging.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Payment outcome applied; booking is now CONFIRMED or FAILED "
            + "(or unchanged, for a retry of an already-decided booking).",
            content = @Content(schema = @Schema(implementation = BookingResponse.class)))
    @ApiResponse(responseCode = "400", description = "The id path parameter could not be parsed as a number.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"INVALID_PARAMETER",\
                            "message":"Parameter 'id' must be of type Long.","path":"/api/bookings/abc/confirm"}""")))
    @ApiResponse(responseCode = "404",
            description = "No booking exists with the given id, or it belongs to a different user - the two are "
                    + "not distinguished (BOOKING_NOT_FOUND, ADR-0008). Can also surface PAYMENT_NOT_FOUND if the "
                    + "booking's payment record is missing, a data-integrity edge case.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "BOOKING_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"BOOKING_NOT_FOUND",\
                            "message":"Booking not found.","path":"/api/bookings/1/confirm"}""")))
    @ApiResponse(responseCode = "401", description = "Authentication is required to access this resource.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "UNAUTHENTICATED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"UNAUTHENTICATED",\
                            "message":"Authentication is required to access this resource.",\
                            "path":"/api/bookings/1/confirm"}""")))
    @PostMapping("/{id}/confirm")
    public BookingResponse confirm(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return bookingService.confirmPayment(principal.id(), id);
    }

    // ErrorCode.BOOKING_NOT_FOUND / PAYMENT_NOT_FOUND (404, ADR-0008), BOOKING_NOT_CONFIRMED (409)
    @Operation(summary = "Cancel a CONFIRMED booking, releasing its seats immediately for others to hold. "
            + "No refund concept exists yet - the payment stays SUCCEEDED.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Booking cancelled; its seats are AVAILABLE again.",
            content = @Content(schema = @Schema(implementation = BookingResponse.class)))
    @ApiResponse(responseCode = "400", description = "The id path parameter could not be parsed as a number.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"INVALID_PARAMETER",\
                            "message":"Parameter 'id' must be of type Long.","path":"/api/bookings/abc/cancel"}""")))
    @ApiResponse(responseCode = "404",
            description = "No booking exists with the given id, or it belongs to a different user - the two are "
                    + "not distinguished (BOOKING_NOT_FOUND, ADR-0008). Can also surface PAYMENT_NOT_FOUND if the "
                    + "booking's payment record is missing, a data-integrity edge case.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "BOOKING_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"BOOKING_NOT_FOUND",\
                            "message":"Booking not found.","path":"/api/bookings/1/cancel"}""")))
    @ApiResponse(responseCode = "409", description = "The booking is not CONFIRMED and so cannot be cancelled.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "BOOKING_NOT_CONFIRMED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":409,"code":"BOOKING_NOT_CONFIRMED",\
                            "message":"Booking is not confirmed.","path":"/api/bookings/1/cancel"}""")))
    @ApiResponse(responseCode = "401", description = "Authentication is required to access this resource.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "UNAUTHENTICATED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"UNAUTHENTICATED",\
                            "message":"Authentication is required to access this resource.",\
                            "path":"/api/bookings/1/cancel"}""")))
    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return bookingService.cancel(principal.id(), id);
    }

    // Literal "/me" is matched ahead of the "/{id}" template by Spring MVC's
    // path specificity rules regardless of declaration order, so this is safe
    // even though it's declared after "/{id}/confirm" above.
    //
    // ErrorCode.PAYMENT_NOT_FOUND (404)
    @Operation(summary = "List every booking belonging to the authenticated caller, newest first.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "The caller's bookings.",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = BookingResponse.class))))
    @ApiResponse(responseCode = "404",
            description = "One of the caller's bookings is missing its payment record - a data-integrity edge "
                    + "case, not something a client action triggers.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "PAYMENT_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"PAYMENT_NOT_FOUND",\
                            "message":"Payment not found.","path":"/api/bookings/me"}""")))
    @ApiResponse(responseCode = "401", description = "Authentication is required to access this resource.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "UNAUTHENTICATED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"UNAUTHENTICATED",\
                            "message":"Authentication is required to access this resource.","path":"/api/bookings/me"}""")))
    @GetMapping("/me")
    public List<BookingResponse> listMine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bookingService.listBookings(principal.id());
    }

    // ErrorCode.BOOKING_NOT_FOUND / PAYMENT_NOT_FOUND (404, ADR-0008)
    @Operation(summary = "Get a single booking belonging to the authenticated caller.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "The booking.",
            content = @Content(schema = @Schema(implementation = BookingResponse.class)))
    @ApiResponse(responseCode = "400", description = "The id path parameter could not be parsed as a number.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"INVALID_PARAMETER",\
                            "message":"Parameter 'id' must be of type Long.","path":"/api/bookings/abc"}""")))
    @ApiResponse(responseCode = "404",
            description = "No booking exists with the given id, or it belongs to a different user - the two are "
                    + "not distinguished (BOOKING_NOT_FOUND, ADR-0008). Can also surface PAYMENT_NOT_FOUND if the "
                    + "booking's payment record is missing, a data-integrity edge case.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "BOOKING_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"BOOKING_NOT_FOUND",\
                            "message":"Booking not found.","path":"/api/bookings/1"}""")))
    @ApiResponse(responseCode = "401", description = "Authentication is required to access this resource.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "UNAUTHENTICATED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"UNAUTHENTICATED",\
                            "message":"Authentication is required to access this resource.","path":"/api/bookings/1"}""")))
    @GetMapping("/{id}")
    public BookingResponse getById(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return bookingService.getBooking(principal.id(), id);
    }
}
