package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.EventSeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {
}
