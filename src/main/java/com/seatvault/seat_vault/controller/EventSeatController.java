package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.ErrorResponse;
import com.seatvault.seat_vault.dto.EventSeatResponse;
import com.seatvault.seat_vault.service.EventSeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only seat/availability browsing for a single event. Public per
 * ADR-0004: seat status (see {@link com.seatvault.seat_vault.entity.EventSeatStatus})
 * is the same regardless of who's asking.
 */
@RestController
@RequestMapping("/api/events/{eventId}/seats")
@RequiredArgsConstructor
public class EventSeatController {

    private final EventSeatService eventSeatService;

    // ErrorCode.EVENT_NOT_FOUND / ErrorCode.INVALID_PARAMETER
    @Operation(summary = "List every seat for an event, with its current availability "
            + "(ADR-0006: this EventSeat id, not the underlying physical Seat id, is the API-level seat reference).")
    @ApiResponse(responseCode = "200", description = "The event's seats.",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = EventSeatResponse.class))))
    @ApiResponse(responseCode = "400", description = "The eventId path parameter could not be parsed as a number.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"INVALID_PARAMETER",\
                            "message":"Parameter 'eventId' must be of type Long.",\
                            "path":"/api/events/abc/seats"}""")))
    @ApiResponse(responseCode = "404", description = "No event exists with the given id.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "EVENT_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"EVENT_NOT_FOUND",\
                            "message":"Event not found.","path":"/api/events/999999/seats"}""")))
    @GetMapping
    public List<EventSeatResponse> listForEvent(@PathVariable Long eventId) {
        return eventSeatService.listForEvent(eventId);
    }
}
