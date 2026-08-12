package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.EventSeat;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

    @Query("""
            select es from EventSeat es
            join fetch es.seat s
            left join fetch es.currentHold
            where es.event.id = :eventId
            order by s.section asc, s.rowLabel asc, s.seatNumber asc
            """)
    List<EventSeat> findByEventIdWithSeatAndHold(@Param("eventId") Long eventId);

    /**
     * The actual concurrency-correctness mechanism per ADR-0001: a Postgres
     * {@code SELECT ... FOR UPDATE} row lock on this seat, held for the
     * duration of the caller's transaction. Every hold-creation path must
     * read the seat through this method, never through plain
     * {@link #findById(Object)}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select es from EventSeat es where es.id = :id")
    Optional<EventSeat> findByIdForUpdate(@Param("id") Long id);

    List<EventSeat> findByCurrentHoldId(Long holdId);

    /**
     * UX-only bulk reconciliation used by {@code HoldSweepService}; the lazy
     * check-on-read in {@code HoldService}/{@code EventSeatAvailability} is
     * what's actually authoritative (ADR-0002).
     */
    @Modifying
    @Query("update EventSeat es set es.status = com.seatvault.seat_vault.entity.EventSeatStatus.AVAILABLE, "
            + "es.currentHold = null "
            + "where es.status = com.seatvault.seat_vault.entity.EventSeatStatus.HELD "
            + "and es.currentHold.expiresAt < :now")
    int releaseExpiredHeldSeats(@Param("now") Instant now);
}
