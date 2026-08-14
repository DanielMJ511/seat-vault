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
import org.springframework.http.HttpStatus;
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
 * right here and never re-read later.
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_SEAT_LIST",
                    "At least one seat must be requested.");
        }
        if (seatIds.size() > holdProperties.maxSeatsPerHold()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_SEATS",
                    "A hold may cover at most " + holdProperties.maxSeatsPerHold() + " seats.");
        }
        if (spansMultipleEvents(seatIds)) {
            // Checked up front, before any locking, so a request that's
            // invalid by shape never pays for lock acquisition it was always
            // going to roll back (see ADR-0006: EventSeat.id already encodes
            // which event a seat belongs to, so this is a cheap lookup, not
            // a client-supplied eventId cross-check).
            throw new ApiException(HttpStatus.BAD_REQUEST, "MULTIPLE_EVENTS_IN_HOLD",
                    "All seats in a hold must belong to the same event.");
        }

        // Seats are locked (both here in Redis and below via Postgres row
        // locks) in a fixed, globally-consistent order (ascending id) so that
        // two concurrent requests targeting overlapping seat sets can never
        // deadlock waiting on each other in opposite orders.
        List<AcquiredLock> acquiredLocks = new ArrayList<>();
        try {
            for (Long seatId : seatIds) {
                Optional<String> token = redisLockService.tryLock(seatId);
                if (token.isEmpty()) {
                    // Genuine contention on this seat right now - fail fast
                    // without ever touching Postgres for this request (the
                    // whole point of the Redis layer per ADR-0001).
                    throw new ApiException(HttpStatus.CONFLICT, "SEAT_ALREADY_HELD",
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
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User no longer exists."));

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
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_SEAT_NOT_FOUND",
                            "Event seat " + seatId + " not found."));

            EventSeatStatus effectiveStatus = EventSeatAvailability.effectiveStatus(eventSeat);
            if (effectiveStatus == EventSeatStatus.BOOKED) {
                // Permanent unavailability for this event - distinct from
                // SEAT_ALREADY_HELD below so a client can tell "pick another
                // seat" from "retry in a bit" (all-or-nothing: rolling back
                // the transaction undoes every mutation made earlier in this
                // loop too).
                throw new ApiException(HttpStatus.CONFLICT, "SEAT_ALREADY_BOOKED",
                        "Seat " + seatId + " has already been booked for this event.");
            }
            if (effectiveStatus != EventSeatStatus.AVAILABLE) {
                throw new ApiException(HttpStatus.CONFLICT, "SEAT_ALREADY_HELD",
                        "Seat " + seatId + " is currently held by another user.");
            }

            Hold staleHold = eventSeat.getCurrentHold();
            if (staleHold != null && staleHold.getStatus() == HoldStatus.ACTIVE) {
                // effectiveStatus() above having returned AVAILABLE while the
                // seat is still stored as HELD means this is a lazily-expired
                // hold (ADR-0002) - reconcile it in this same transaction
                // before handing the seat to the new hold.
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

    @Transactional
    public void releaseHold(Long userId, Long holdId) {
        // Locked (not a plain findById) so this can't race with
        // BookingService#createFromHold converting the same Hold: whichever
        // of the two commits first, the other blocks here and then re-reads
        // the committed status below, rather than blindly overwriting it.
        Hold hold = holdRepository.findByIdForUpdate(holdId)
                .filter(h -> h.getUser().getId().equals(userId))
                // Same code whether the hold doesn't exist at all or belongs
                // to someone else - don't let a non-owner distinguish the two.
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "Hold not found."));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Hold is not active.");
        }

        // findByCurrentHoldId is an unlocked read, good enough to gather
        // candidate seat ids, but the actual read-check-write must happen
        // under each seat's own row lock (same as createHoldWithSeatsLocked)
        // - otherwise a concurrent createHold that has already reassigned
        // this seat to a brand-new hold (via lazy-expiry reconciliation,
        // ADR-0002) could be silently clobbered by this release. Sorted
        // ascending for the same deadlock-avoidance reason as createHold.
        List<Long> seatIds = eventSeatRepository.findByCurrentHoldId(holdId).stream()
                .map(EventSeat::getId)
                .sorted()
                .toList();

        for (Long seatId : seatIds) {
            EventSeat eventSeat = eventSeatRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_SEAT_NOT_FOUND",
                            "Event seat " + seatId + " not found."));

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
