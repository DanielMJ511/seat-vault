package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.HoldSeat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldSeatRepository extends JpaRepository<HoldSeat, Long> {

    List<HoldSeat> findByHoldId(Long holdId);
}
