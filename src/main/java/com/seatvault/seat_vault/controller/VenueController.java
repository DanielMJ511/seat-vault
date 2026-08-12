package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.VenueResponse;
import com.seatvault.seat_vault.service.VenueService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @GetMapping
    public List<VenueResponse> listAll() {
        return venueService.listAll();
    }

    @GetMapping("/{id}")
    public VenueResponse getById(@PathVariable Long id) {
        return venueService.getById(id);
    }
}
