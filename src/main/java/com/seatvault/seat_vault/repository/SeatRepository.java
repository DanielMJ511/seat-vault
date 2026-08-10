package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {
}
