package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.Venue;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    List<Venue> findAllByOrderByNameAsc();
}
