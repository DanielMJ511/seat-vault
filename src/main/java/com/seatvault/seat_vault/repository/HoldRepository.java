package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.Hold;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldRepository extends JpaRepository<Hold, Long> {

    /**
     * A Postgres {@code SELECT ... FOR UPDATE} row lock on this Hold, held
     * for the duration of the caller's transaction. Mandatory for any code
     * that reads a Hold and then writes its {@code status} - e.g. {@code
     * HoldService#releaseHold} and {@code BookingService#createFromHold} -
     * since without it, two concurrent status-changing operations on the same
     * Hold (a release racing a booking-conversion) can interleave so that the
     * loser's stale in-memory status overwrites the winner's committed one,
     * even though the seat-level locking (ADR-0001) prevented any seat from
     * being double-assigned.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.id = :id")
    Optional<Hold> findByIdForUpdate(@Param("id") Long id);

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
