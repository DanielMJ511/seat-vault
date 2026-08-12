package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.EventSeatResponse;
import com.seatvault.seat_vault.service.EventSeatService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{eventId}/seats")
@RequiredArgsConstructor
public class EventSeatController {

    private final EventSeatService eventSeatService;

    @GetMapping
    public List<EventSeatResponse> listForEvent(@PathVariable Long eventId) {
        return eventSeatService.listForEvent(eventId);
    }
}
