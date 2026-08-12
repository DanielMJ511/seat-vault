package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.Hold;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldRepository extends JpaRepository<Hold, Long> {

    /**
     * UX-only bulk reconciliation used by {@code HoldSweepService} (ADR-0002:
     * the lazy check-on-read inside {@code HoldService} is what's actually
     * authoritative for correctness — this just keeps stored state from
     * looking stale to a casual browser).
     */
    @Modifying
    @Query("update Hold h set h.status = com.seatvault.seat_vault.entity.HoldStatus.EXPIRED "
            + "where h.status = com.seatvault.seat_vault.entity.HoldStatus.ACTIVE and h.expiresAt < :now")
    int expireOverdueHolds(@Param("now") Instant now);
}
