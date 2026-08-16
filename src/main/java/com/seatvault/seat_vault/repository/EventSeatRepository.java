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

    /**
     * Projects the ids of the seats a hold currently points at, deliberately
     * never materializing {@code EventSeat} entities - the same discipline,
     * and for the same reason, as {@link #findDistinctEventIdsByIdIn} below
     * (ADR-0010). {@code HoldService#releaseHold} uses this to gather its
     * candidate seats before locking each one with {@link
     * #findByIdForUpdate}; the obvious {@code findByCurrentHoldId} spelling
     * of this query returned managed entities and silently defeated that
     * lock, so it was deleted rather than left available to be reached for
     * again. See {@code HoldReleaseSeatLockRaceIntegrationTest} for the race
     * that pins it.
     */
    @Query("select es.id from EventSeat es where es.currentHold.id = :holdId")
    List<Long> findIdsByCurrentHoldId(@Param("holdId") Long holdId);

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
     * (T-003) for the race that caught this, and ADR-0010 for the general
     * rule this and {@link #findIdsByCurrentHoldId} both exist to keep.
     */
    @Query("select distinct es.event.id from EventSeat es where es.id in :ids")
    List<Long> findDistinctEventIdsByIdIn(@Param("ids") List<Long> ids);

    /**
     * Locks, in ascending id order, every {@code event_seats} row {@code
     * HoldSweepService} is about to release, and returns their ids - the
     * same ordering discipline every other multi-seat path uses (ADR-0011's
     * "seat-versus-seat residual, closed in M8 (#16)"). Without this, the
     * bulk {@code UPDATE} below would lock the rows it matches in whatever
     * order Postgres's plan produced them, which can disagree with a
     * concurrent multi-seat {@code createHold} locking the same two seats
     * and deadlock it - a legitimate request turned into a 500.
     *
     * <p><b>Native, not JPQL.</b> Hibernate does not reliably emit {@code
     * FOR UPDATE} for a scalar JPQL projection, so the row-locking {@code
     * ORDER BY ... FOR UPDATE} has to be hand-written and its generated SQL
     * confirmed directly, not trusted from the annotation. <b>{@code FOR
     * UPDATE OF es}, never bare {@code FOR UPDATE}</b>: the predicate joins
     * {@code holds}, and a bare {@code FOR UPDATE} would take the {@code
     * holds} row lock too, in this same statement - fixing the
     * seat-versus-seat ordering while breaking the seats-before-holds
     * ordering ADR-0011 requires everywhere else. Confirmed against real
     * Postgres that {@code LockRows} sits above {@code Sort} in this query's
     * plan, i.e. the sort runs first and rows are locked in the ascending
     * order it produces, not in the join's own scan order.
     *
     * <p>Still a scalar id projection per ADR-0010 - {@code
     * HoldSweepService} passes the returned ids straight into {@link
     * #releaseExpiredHeldSeats} without ever materializing an {@code
     * EventSeat} entity.
     */
    @Query(
            value = """
                    select es.id from event_seats es
                    join holds ch on ch.id = es.current_hold_id
                    where es.status = 'HELD'
                      and ch.expires_at < :now
                    order by es.id
                    for update of es
                    """,
            nativeQuery = true)
    List<Long> findIdsOfExpiredHeldSeatsForUpdate(@Param("now") Instant now);

    /**
     * UX-only bulk reconciliation used by {@code HoldSweepService}; the lazy
     * check-on-read in {@code HoldService}/{@code EventSeatAvailability} is
     * what's actually authoritative (ADR-0002).
     *
     * <p>{@code ids} is expected to be exactly what {@link
     * #findIdsOfExpiredHeldSeatsForUpdate} just locked, in the same
     * transaction - this statement's own scan order therefore no longer
     * matters, it only re-touches rows already locked. The rest of the
     * predicate is kept anyway (rather than trusting {@code ids} alone) so a
     * stale or empty list is a no-op instead of a wrong write; callers must
     * still skip calling this at all for an empty list, since {@code id in
     * ()} is invalid SQL.
     */
    @Modifying
    @Query("update EventSeat es set es.status = com.seatvault.seat_vault.entity.EventSeatStatus.AVAILABLE, "
            + "es.currentHold = null "
            + "where es.id in :ids "
            + "and es.status = com.seatvault.seat_vault.entity.EventSeatStatus.HELD "
            + "and es.currentHold.expiresAt < :now")
    int releaseExpiredHeldSeats(@Param("ids") List<Long> ids, @Param("now") Instant now);
}
