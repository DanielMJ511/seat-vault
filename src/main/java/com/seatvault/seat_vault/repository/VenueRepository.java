package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {
}
