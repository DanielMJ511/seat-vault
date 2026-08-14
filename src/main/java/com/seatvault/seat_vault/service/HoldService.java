package com.seatvault.seat_vault.service;

import com.seatvault.seat_vault.config.HoldProperties;
import com.seatvault.seat_vault.dto.HoldRequest;
import com.seatvault.seat_vault.dto.HoldResponse;
import com.seatvault.seat_vault.dto.HoldSeatResponse;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import com.seatvault.seat_vault.entity.Hold;
import com.seatvault.seat_vault.entity.HoldSeat;
import com.seatvault.seat_vault.entity.HoldStatus;
import com.seatvault.seat_vault.entity.User;
import com.seatvault.seat_vault.exception.ApiException;
import com.seatvault.seat_vault.exception.ErrorCode;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.HoldRepository;
import com.seatvault.seat_vault.repository.HoldSeatRepository;
import com.seatvault.seat_vault.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The concurrency-critical hold create/release path. Correctness rests on
 * ADR-0001 (Postgres's {@code SELECT ... FOR UPDATE}, via
 * {@link EventSeatRepository#findByIdForUpdate(Long)}, is authoritative; the
 * Redis lock in {@link RedisLockService} is a non-authoritative fail-fast
 * optimization only) and ADR-0002 (a stale {@code HELD} seat past its hold's
 * expiry is reconciled lazily, inside the very transaction that's about to
 * reuse it, rather than depending on the scheduled sweep). ADR-0003 is why
 * {@link HoldSeat#getPriceSnapshot()} is captured from {@link EventSeat#getPrice()}
 * right here and never re-read later. ADR-0010 is why neither
 * {@code createHold}'s pre-check nor {@code releaseHold}'s candidate lookup
 * may load an {@code EventSeat} entity before the locking loop runs, and
 * ADR-0011 is why both methods - and every other path in the codebase that
 * touches both tables - take their {@code event_seats} locks before their
 * {@code holds} lock, including the one {@code createHold} takes without
 * looking like it does.
 */
@Service
@RequiredArgsConstructor
public class HoldService {

    private final HoldRepository holdRepository;
    private final HoldSeatRepository holdSeatRepository;
    private final EventSeatRepository eventSeatRepository;
    private final UserRepository userRepository;
    private final RedisLockService redisLockService;
    private final HoldProperties holdProperties;

    @Transactional
    public HoldResponse createHold(Long userId, HoldRequest request) {
        List<Long> seatIds = request.eventSeatIds().stream().distinct().sorted().toList();

        if (seatIds.isEmpty()) {
            throw new ApiException(ErrorCode.EMPTY_SEAT_LIST,
                    "At least one seat must be requested.");
        }
        if (seatIds.size() > holdProperties.maxSeatsPerHold()) {
            throw new ApiException(ErrorCode.TOO_MANY_SEATS,
                    "A hold may cover at most " + holdProperties.maxSeatsPerHold() + " seats.");
        }
        if (spansMultipleEvents(seatIds)) {
            // Checked up front, before any locking, so a request that's
            // invalid by shape never pays for lock acquisition it was always
            // going to roll back (see ADR-0006: EventSeat.id already encodes
            // which event a seat belongs to, so this is a cheap lookup, not
            // a client-supplied eventId cross-check).
            throw new ApiException(ErrorCode.MULTIPLE_EVENTS_IN_HOLD,
                    "All seats in a hold must belong to the same event.");
        }

        // Seats are locked (both here in Redis and below via Postgres row
        // locks) in a fixed, globally-consistent order (ascending id) so that
        // two concurrent requests targeting overlapping seat sets can never
        // deadlock waiting on each other in opposite orders. That is only
        // half the ordering rule: this method also locks the holds row of any
        // hold it lazily expires, and it does so *after* the seat locks (see
        // createHoldWithSeatsLocked). Seats before holds is the order every
        // path in the codebase now keeps - ADR-0011.
        List<AcquiredLock> acquiredLocks = new ArrayList<>();
        try {
            for (Long seatId : seatIds) {
                Optional<String> token = redisLockService.tryLock(seatId);
                if (token.isEmpty()) {
                    // Genuine contention on this seat right now - fail fast
                    // without ever touching Postgres for this request (the
                    // whole point of the Redis layer per ADR-0001).
                    throw new ApiException(ErrorCode.SEAT_ALREADY_HELD,
                            "Seat " + seatId + " is currently being requested by another user.");
                }
                acquiredLocks.add(new AcquiredLock(seatId, token.get()));
            }

            return createHoldWithSeatsLocked(userId, seatIds);
        } finally {
            for (AcquiredLock lock : acquiredLocks) {
                redisLockService.unlock(lock.seatId(), lock.token());
            }
        }
    }

    private HoldResponse createHoldWithSeatsLocked(Long userId, List<Long> seatIds) {
        Instant now = Instant.now();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "User no longer exists."));

        Hold hold = holdRepository.save(Hold.builder()
                .user(user)
                .status(HoldStatus.ACTIVE)
                .expiresAt(now.plus(holdProperties.ttlMinutes(), ChronoUnit.MINUTES))
                .build());

        List<HoldSeat> holdSeats = new ArrayList<>(seatIds.size());
        for (Long seatId : seatIds) {
            // The actual correctness mechanism: a Postgres row lock, held
            // until this transaction commits or rolls back.
            EventSeat eventSeat = eventSeatRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new ApiException(ErrorCode.EVENT_SEAT_NOT_FOUND,
                            "Event seat " + seatId + " not found."));

            EventSeatStatus effectiveStatus = EventSeatAvailability.effectiveStatus(eventSeat);
            if (effectiveStatus == EventSeatStatus.BOOKED) {
                // Permanent unavailability for this event - distinct from
                // SEAT_ALREADY_HELD below so a client can tell "pick another
                // seat" from "retry in a bit" (all-or-nothing: rolling back
                // the transaction undoes every mutation made earlier in this
                // loop too).
                throw new ApiException(ErrorCode.SEAT_ALREADY_BOOKED,
                        "Seat " + seatId + " has already been booked for this event.");
            }
            if (effectiveStatus != EventSeatStatus.AVAILABLE) {
                throw new ApiException(ErrorCode.SEAT_ALREADY_HELD,
                        "Seat " + seatId + " is currently held by another user.");
            }

            Hold staleHold = eventSeat.getCurrentHold();
            if (staleHold != null && staleHold.getStatus() == HoldStatus.ACTIVE) {
                // effectiveStatus() above having returned AVAILABLE while the
                // seat is still stored as HELD means this is a lazily-expired
                // hold (ADR-0002) - reconcile it in this same transaction
                // before handing the seat to the new hold.
                //
                // THIS LINE TAKES A ROW LOCK, even though nothing about it
                // looks like one: dirtying a managed entity makes Hibernate
                // issue an UPDATE at flush time, and that UPDATE locks the
                // holds row for the rest of this transaction (ADR-0011). It
                // is the second half of this method's lock order - seats
                // first, holds second - and it is why releaseHold and
                // BookingService#createFromHold had to be inverted to match
                // rather than this being moved out of the seat loop. Adding
                // any read-then-write of a *third* table inside this loop
                // needs the same care.
                //
                // Deliberately not routed through HoldExpiry.isExpired(): the
                // time comparison it would duplicate has already happened,
                // inside effectiveStatus() above. This ACTIVE check is a
                // different question - "has something already reconciled this
                // Hold" - guarding idempotency, not re-deriving expiry.
                staleHold.setStatus(HoldStatus.EXPIRED);
            }

            eventSeat.setStatus(EventSeatStatus.HELD);
            eventSeat.setCurrentHold(hold);

            holdSeats.add(HoldSeat.builder()
                    .hold(hold)
                    .eventSeat(eventSeat)
                    .priceSnapshot(eventSeat.getPrice())
                    .build());
        }

        holdSeatRepository.saveAll(holdSeats);

        return toResponse(hold, holdSeats);
    }

    /**
     * Lock order here is load-bearing and deliberately counter-intuitive:
     * <b>every {@code event_seats} row first (ascending id), the {@code holds}
     * row second</b> (ADR-0011). Authorizing the caller before doing any work
     * would be the natural way to write this, and is how it was written until
     * T-007 - but locking the {@code holds} row first made this the only
     * method in the codebase that took these two tables in that order, and it
     * deadlocked against {@code createHold} and {@code
     * HoldSweepService#sweepExpiredHolds}, both of which take seats first.
     * Postgres broke the cycle by killing one of the two, so a legitimate
     * request got a 500. Pinned by {@code
     * HoldLockOrderDeadlockIntegrationTest}.
     *
     * <p>Two consequences of the order that are accepted, not overlooked. The
     * seat locks are taken before the caller is known to be the owner, so a
     * request for someone else's hold briefly contends on that owner's seats
     * before being rejected - the locks last microseconds and are rolled back,
     * where a deadlocked release costs a user-visible error. And the hold's
     * status is now read <em>after</em> the seats are pinned, so a concurrent
     * {@code createHold} that lazily expired this hold (ADR-0002) can win the
     * race and turn this call into a 409 that would previously have been a
     * 204. That is the correct answer for a hold that really has expired
     * (ADR-0007), not a regression.
     */
    @Transactional
    public void releaseHold(Long userId, Long holdId) {
        // An unlocked read, good enough to gather candidate seat ids: the set
        // can only shrink under us (nothing ever adds a seat to an existing
        // hold), and each id is re-checked below under its own row lock.
        // Sorted ascending, the same global seat order createHold uses.
        //
        // It must be a scalar id projection, never an entity query such as
        // the findByCurrentHoldId this used to call (ADR-0010): loading
        // managed EventSeat entities here would put this transaction's
        // pre-lock snapshot of them into the Hibernate session, and the
        // identity map would then hand that same stale instance back to the
        // findByIdForUpdate below instead of the freshly-locked row - so the
        // ownership guard would re-check the value it had already read and
        // the row lock would decide nothing. That is not hypothetical: with
        // the entity query in place, HoldReleaseSeatLockRaceIntegrationTest
        // reproducibly destroyed a live hold belonging to another user.
        List<Long> seatIds = eventSeatRepository.findIdsByCurrentHoldId(holdId).stream()
                .sorted()
                .toList();

        List<EventSeat> lockedSeats = new ArrayList<>(seatIds.size());
        for (Long seatId : seatIds) {
            lockedSeats.add(eventSeatRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new ApiException(ErrorCode.EVENT_SEAT_NOT_FOUND,
                            "Event seat " + seatId + " not found.")));
        }

        // Only now the holds row. Locked (not a plain findById) so this can't
        // race with BookingService#createFromHold converting the same Hold:
        // whichever of the two commits first, the other blocks here and then
        // re-reads the committed status, rather than blindly overwriting it.
        //
        // Nothing above may have loaded this Hold as an initialized entity,
        // or the identity map would serve this locked read from that pre-lock
        // snapshot (ADR-0010). It hasn't: the projection materializes no
        // entity, and EventSeat#currentHold is a lazy proxy that the loop
        // above never touches.
        Hold hold = holdRepository.findByIdForUpdate(holdId)
                .filter(h -> h.getUser().getId().equals(userId))
                // Same code whether the hold doesn't exist at all or belongs
                // to someone else - don't let a non-owner distinguish the two.
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_NOT_FOUND, "Hold not found."));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            // ADR-0009: the split is keyed on domain state via HoldExpiry, not
            // on the fact that this particular check fired - a hold whose
            // stored status is already EXPIRED (sweep, or ADR-0007's manual
            // release) reports HOLD_EXPIRED same as the lazy-expiry path
            // elsewhere does; only a CONVERTED hold - which can't happen for
            // releaseHold in practice, since a converted hold no longer owns
            // any seats for a non-owner to have raced in on, but is not
            // provably impossible - falls through to HOLD_NOT_ACTIVE.
            if (HoldExpiry.isExpired(hold)) {
                throw new ApiException(ErrorCode.HOLD_EXPIRED, "Hold has expired.");
            }
            throw new ApiException(ErrorCode.HOLD_NOT_ACTIVE, "Hold is not active.");
        }

        for (EventSeat eventSeat : lockedSeats) {
            // getId() on the association reads the proxy's identifier without
            // initializing it, so this guard still sees the locked row's
            // current_hold_id rather than any earlier snapshot.
            Hold currentHold = eventSeat.getCurrentHold();
            if (currentHold != null && currentHold.getId().equals(holdId)) {
                eventSeat.setStatus(EventSeatStatus.AVAILABLE);
                eventSeat.setCurrentHold(null);
            }
            // else: this seat has already moved on to a different hold since
            // the candidate list was read above - leave it untouched rather
            // than releasing a seat this hold no longer actually owns.
        }

        // Confirmed decision: a manually-released hold becomes EXPIRED, the
        // same value a timed-out hold gets - "released" is an API-level
        // action, not a distinct stored state.
        hold.setStatus(HoldStatus.EXPIRED);
    }

    /**
     * A plain unlocked lookup, deliberately not routed through {@code
     * findByIdForUpdate}: this is a request-shape check (does the request
     * even make sense?), not a concurrency-sensitive one, and running it
     * before any locking keeps a malformed request cheap to reject. Missing
     * seat ids are silently skipped here - they're reported precisely as
     * {@code EVENT_SEAT_NOT_FOUND} later, once locking has begun.
     *
     * <p>Deliberately a projection ({@link
     * EventSeatRepository#findDistinctEventIdsByIdIn}), not {@code
     * findAllById}: the latter would load full, managed {@code EventSeat}
     * entities - with whatever status they had at this unlocked read - into
     * this transaction's Hibernate persistence context before the real
     * locking loop below even starts. Because a JPA session's identity map
     * returns the same already-managed instance for a given id rather than
     * refreshing it, the later, correctly-locked {@code findByIdForUpdate}
     * call for that same seat would silently hand back this stale,
     * unlocked-read snapshot instead of the fresh, lock-protected row -
     * defeating the Postgres lock entirely for any request that overlaps
     * with another. This was a real, confirmed bug (not hypothetical):
     * {@code HoldRedisUnavailableRaceIntegrationTest}'s overlapping-seat race
     * (T-003) reproducibly double-booked seats with the {@code findAllById}
     * version whenever a request covered 2+ seats. A pure id/event-id
     * projection never enters the persistence context at all, so it cannot
     * poison the later locked read.
     */
    private boolean spansMultipleEvents(List<Long> seatIds) {
        if (seatIds.size() < 2) {
            return false;
        }
        return eventSeatRepository.findDistinctEventIdsByIdIn(seatIds).size() > 1;
    }

    private static HoldResponse toResponse(Hold hold, List<HoldSeat> holdSeats) {
        List<HoldSeatResponse> seats = holdSeats.stream()
                .map(holdSeat -> new HoldSeatResponse(
                        holdSeat.getEventSeat().getId(),
                        holdSeat.getEventSeat().getSeat().getSection(),
                        holdSeat.getEventSeat().getSeat().getRowLabel(),
                        holdSeat.getEventSeat().getSeat().getSeatNumber(),
                        holdSeat.getPriceSnapshot()))
                .toList();

        return new HoldResponse(hold.getId(), hold.getStatus(), hold.getExpiresAt(), seats);
    }

    private record AcquiredLock(Long seatId, String token) {
    }
}
