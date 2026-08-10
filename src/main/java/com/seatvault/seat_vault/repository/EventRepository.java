package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
}
