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
     * Projects only the distinct {@code event_id}s for a set of seat ids,
     * deliberately never materializing full {@code EventSeat} entities into
     * the caller's persistence context. {@link HoldService#spansMultipleEvents}
     * needs this as a cheap, pre-locking request-shape check; a plain {@code
     * findAllById} would work too, but would load managed {@code EventSeat}
     * entities (with their status as of that unlocked read) into the same
     * transaction's Hibernate session <em>before</em> the real locking loop
     * begins. Hibernate's identity map then reuses that already-managed,
     * stale instance for the later, correctly-locked {@code
     * findByIdForUpdate} call on the same id, instead of refreshing it from
     * the fresh, lock-protected row - silently defeating the lock under real
     * concurrent contention. See {@code HoldRedisUnavailableRaceIntegrationTest}
     * (T-003) for the race that caught this.
     */
    @Query("select distinct es.event.id from EventSeat es where es.id in :ids")
    List<Long> findDistinctEventIdsByIdIn(@Param("ids") List<Long> ids);

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
