package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.ErrorResponse;
import com.seatvault.seat_vault.dto.VenueResponse;
import com.seatvault.seat_vault.service.VenueService;
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
 * Read-only venue browsing. Public per ADR-0004: the response is the same
 * regardless of who's asking, or whether anyone's asking at all.
 */
@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @Operation(summary = "List all venues, ordered by name.")
    @ApiResponse(responseCode = "200", description = "All venues.",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = VenueResponse.class))))
    @GetMapping
    public List<VenueResponse> listAll() {
        return venueService.listAll();
    }

    // ErrorCode.VENUE_NOT_FOUND / ErrorCode.INVALID_PARAMETER
    @Operation(summary = "Get a single venue by id.")
    @ApiResponse(responseCode = "200", description = "The venue.",
            content = @Content(schema = @Schema(implementation = VenueResponse.class)))
    @ApiResponse(responseCode = "400", description = "The id path parameter could not be parsed as a number.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"INVALID_PARAMETER",\
                            "message":"Parameter 'id' must be of type Long.","path":"/api/venues/abc"}""")))
    @ApiResponse(responseCode = "404", description = "No venue exists with the given id.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "VENUE_NOT_FOUND", value = """
                            {"timestamp":"2026-01-01T00:00:00Z","status":404,"code":"VENUE_NOT_FOUND",\
                            "message":"Venue not found.","path":"/api/venues/999999"}""")))
    @GetMapping("/{id}")
    public VenueResponse getById(@PathVariable Long id) {
        return venueService.getById(id);
    }
}
