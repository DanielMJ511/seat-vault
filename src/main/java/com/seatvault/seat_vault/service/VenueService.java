package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.dto.VenueResponse;
import com.seatvault.seat_vault.entity.Venue;
import com.seatvault.seat_vault.exception.ApiException;
import com.seatvault.seat_vault.exception.ErrorCode;
import com.seatvault.seat_vault.repository.VenueRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VenueService {

    private final VenueRepository venueRepository;

    @Transactional(readOnly = true)
    public List<VenueResponse> listAll() {
        return venueRepository.findAllByOrderByNameAsc().stream()
                .map(VenueService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public VenueResponse getById(Long id) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.VENUE_NOT_FOUND, "Venue not found."));

        return toResponse(venue);
    }

    private static VenueResponse toResponse(Venue venue) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getAddress(), venue.getCreatedAt());
    }
}
