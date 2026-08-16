package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.ErrorResponse;
import com.seatvault.seat_vault.dto.HoldRequest;
import com.seatvault.seat_vault.dto.HoldResponse;
import com.seatvault.seat_vault.security.AuthenticatedUser;
import com.seatvault.seat_vault.service.HoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-scoped: every operation here is authenticated per ADR-0004, by way of
 * {@code SecurityConfig}'s deny-by-default chain - neither route appears on
 * its list of public routes, so both fall through to
 * {@code anyRequest().authenticated()}.
 */
@RestController
@RequestMapping("/api/holds")
@RequiredArgsConstructor
public class HoldController {

    private final HoldService holdService;

    // ErrorCode.EMPTY_SEAT_LIST / TOO_MANY_SEATS / MULTIPLE_EVENTS_IN_HOLD / VALIDATION_ERROR (400)
    // ErrorCode.EVENT_SEAT_NOT_FOUND / USER_NOT_FOUND (404)
    // ErrorCode.SEAT_ALREADY_HELD / SEAT_ALREADY_BOOKED (409)
    @Operation(summary = "Place a temporary hold on one or more event seats ahead of booking. "
            + "Seats are locked in a fixed ascending-id order to avoid deadlocking concurrent requests.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Hold created; seats are HELD until it expires or converts.",
            content = @Content(schema = @Schema(implementation = HoldResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "Malformed request: an empty seat list (EMPTY_SEAT_LIST), more seats than the "
                    + "configured maximum (TOO_MANY_SEATS), seats spanning more than one event "
                    + "(MULTIPLE_EVENTS_IN_HOLD), or a bean-validation failure (VALIDATION_ERROR).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "EMPTY_SEAT_LIST", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"EMPTY_SEAT_LIST",\
                            "message":"At least one seat must be requested.","path":"/api/holds"}""")))
    @ApiResponse(responseCode = "404",
            description = "One of the requested event seat ids doesn't exist (EVENT_SEAT_NOT_FOUND); or, "
                    + "extremely rarely, the authenticated caller's account no longer exists (USER_NOT_FOUND).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "EVENT_SEAT_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"EVENT_SEAT_NOT_FOUND",\
                            "message":"Event seat 42 not found.","path":"/api/holds"}""")))
    @ApiResponse(responseCode = "409",
            description = "A requested seat is currently held by someone else - retry shortly "
                    + "(SEAT_ALREADY_HELD); or it has already been booked for this event - choose another seat "
                    + "(SEAT_ALREADY_BOOKED).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "SEAT_ALREADY_HELD", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":409,"code":"SEAT_ALREADY_HELD",\
                            "message":"Seat 42 is currently being requested by another user.",\
                            "path":"/api/holds"}""")))
    @ApiResponse(responseCode = "401", description = "Authentication is required to access this resource.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "UNAUTHENTICATED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"UNAUTHENTICATED",\
                            "message":"Authentication is required to access this resource.","path":"/api/holds"}""")))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody HoldRequest request) {
        return holdService.createHold(principal.id(), request);
    }

    // ErrorCode.EVENT_SEAT_NOT_FOUND / HOLD_NOT_FOUND (404), HOLD_EXPIRED / HOLD_NOT_ACTIVE (409)
    @Operation(summary = "Release an active hold early, freeing its seats immediately.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "Hold released; its seats are AVAILABLE again.")
    @ApiResponse(responseCode = "400", description = "The id path parameter could not be parsed as a number.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"INVALID_PARAMETER",\
                            "message":"Parameter 'id' must be of type Long.","path":"/api/holds/abc"}""")))
    @ApiResponse(responseCode = "404",
            description = "No hold exists with the given id, or it belongs to a different user - the two are "
                    + "not distinguished (HOLD_NOT_FOUND). Can also surface EVENT_SEAT_NOT_FOUND defensively.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "HOLD_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"HOLD_NOT_FOUND",\
                            "message":"Hold not found.","path":"/api/holds/1"}""")))
    @ApiResponse(responseCode = "409",
            description = "The hold's countdown already ran out - start a new hold (HOLD_EXPIRED); or it isn't "
                    + "in a releasable state, e.g. already converted into a booking (HOLD_NOT_ACTIVE).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "HOLD_NOT_ACTIVE", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":409,"code":"HOLD_NOT_ACTIVE",\
                            "message":"Hold is not active.","path":"/api/holds/1"}""")))
    @ApiResponse(responseCode = "401", description = "Authentication is required to access this resource.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "UNAUTHENTICATED", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"UNAUTHENTICATED",\
                            "message":"Authentication is required to access this resource.","path":"/api/holds/1"}""")))
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        holdService.releaseHold(principal.id(), id);
    }
}
