package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.ErrorResponse;
import com.seatvault.seat_vault.dto.EventResponse;
import com.seatvault.seat_vault.service.EventService;
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
 * Read-only event browsing. Public per ADR-0004: the response is the same
 * regardless of who's asking, or whether anyone's asking at all.
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @Operation(summary = "List all events, ordered by start time.")
    @ApiResponse(responseCode = "200", description = "All events.",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = EventResponse.class))))
    @GetMapping
    public List<EventResponse> listAll() {
        return eventService.listAll();
    }

    // ErrorCode.EVENT_NOT_FOUND / ErrorCode.INVALID_PARAMETER
    @Operation(summary = "Get a single event by id.")
    @ApiResponse(responseCode = "200", description = "The event.",
            content = @Content(schema = @Schema(implementation = EventResponse.class)))
    @ApiResponse(responseCode = "400", description = "The id path parameter could not be parsed as a number.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"INVALID_PARAMETER",\
                            "message":"Parameter 'id' must be of type Long.","path":"/api/events/abc"}""")))
    @ApiResponse(responseCode = "404", description = "No event exists with the given id.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "EVENT_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"EVENT_NOT_FOUND",\
                            "message":"Event not found.","path":"/api/events/999999"}""")))
    @GetMapping("/{id}")
    public EventResponse getById(@PathVariable Long id) {
        return eventService.getById(id);
    }
}
