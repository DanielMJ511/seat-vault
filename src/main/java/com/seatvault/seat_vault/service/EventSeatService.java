package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.dto.EventSeatResponse;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.exception.ApiException;
import com.seatvault.seat_vault.repository.EventRepository;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventSeatService {

    private final EventRepository eventRepository;
    private final EventSeatRepository eventSeatRepository;

    @Transactional(readOnly = true)
    public List<EventSeatResponse> listForEvent(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event not found.");
        }

        return eventSeatRepository.findByEventIdWithSeatAndHold(eventId).stream()
                .map(EventSeatService::toResponse)
                .toList();
    }

    private static EventSeatResponse toResponse(EventSeat eventSeat) {
        return new EventSeatResponse(
                eventSeat.getId(),
                eventSeat.getSeat().getSection(),
                eventSeat.getSeat().getRowLabel(),
                eventSeat.getSeat().getSeatNumber(),
                eventSeat.getPrice(),
                EventSeatAvailability.effectiveStatus(eventSeat));
    }
}
