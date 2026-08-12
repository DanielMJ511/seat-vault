package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.EventResponse;
import com.seatvault.seat_vault.service.EventService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public List<EventResponse> listAll() {
        return eventService.listAll();
    }

    @GetMapping("/{id}")
    public EventResponse getById(@PathVariable Long id) {
        return eventService.getById(id);
    }
}
