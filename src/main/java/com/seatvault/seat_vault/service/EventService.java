package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.dto.EventResponse;
import com.seatvault.seat_vault.entity.Event;
import com.seatvault.seat_vault.exception.ApiException;
import com.seatvault.seat_vault.repository.EventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;

    @Transactional(readOnly = true)
    public List<EventResponse> listAll() {
        return eventRepository.findAllWithVenueOrderByStartsAtAsc().stream()
                .map(EventService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse getById(Long id) {
        Event event = eventRepository.findByIdWithVenue(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event not found."));

        return toResponse(event);
    }

    private static EventResponse toResponse(Event event) {
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getStartsAt(),
                event.getVenue().getId(),
                event.getVenue().getName());
    }
}
