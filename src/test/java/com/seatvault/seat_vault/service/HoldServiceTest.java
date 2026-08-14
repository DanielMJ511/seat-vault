package com.seatvault.seat_vault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatvault.seat_vault.config.HoldProperties;
import com.seatvault.seat_vault.dto.HoldRequest;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import com.seatvault.seat_vault.entity.Hold;
import com.seatvault.seat_vault.entity.HoldStatus;
import com.seatvault.seat_vault.entity.User;
import com.seatvault.seat_vault.exception.ApiException;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.HoldRepository;
import com.seatvault.seat_vault.repository.HoldSeatRepository;
import com.seatvault.seat_vault.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Pure-mock unit coverage of {@link HoldService}'s branching logic (dedupe/
 * validation, Redis fail-fast + cleanup, Postgres-stage rejection, and
 * release ownership/state checks). The concurrency-correctness itself (the
 * actual row locking) is covered against a real Postgres instance by {@code
 * controller.HoldIntegrationTest} instead - Mockito can't meaningfully
 * exercise {@code SELECT ... FOR UPDATE} semantics.
 */
@ExtendWith(MockitoExtension.class)
class HoldServiceTest {

    @Mock
    private HoldRepository holdRepository;

    @Mock
    private HoldSeatRepository holdSeatRepository;

    @Mock
    private EventSeatRepository eventSeatRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RedisLockService redisLockService;

    private final HoldProperties holdProperties = new HoldProperties(5, 8, 10);

    private HoldService newHoldService() {
        return new HoldService(
                holdRepository, holdSeatRepository, eventSeatRepository, userRepository, redisLockService,
                holdProperties);
    }

    @Test
    void createHoldWithEmptySeatListIsRejected() {
        HoldService service = newHoldService();

        assertThatThrownBy(() -> service.createHold(1L, new HoldRequest(List.of())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiException.getCode()).isEqualTo("EMPTY_SEAT_LIST");
                });
    }

    @Test
    void createHoldWithTooManySeatsIsRejected() {
        HoldService service = newHoldService();
        List<Long> seatIds = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);

        assertThatThrownBy(() -> service.createHold(1L, new HoldRequest(seatIds)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiException.getCode()).isEqualTo("TOO_MANY_SEATS");
                });

        verify(eventSeatRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void createHoldSpanningMultipleEventsIsRejected() {
        HoldService service = newHoldService();

        when(eventSeatRepository.findDistinctEventIdsByIdIn(List.of(1L, 2L))).thenReturn(List.of(1L, 2L));

        assertThatThrownBy(() -> service.createHold(1L, new HoldRequest(List.of(1L, 2L))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiException.getCode()).isEqualTo("MULTIPLE_EVENTS_IN_HOLD");
                });

        // Rejected before any locking, since this is a request-shape check.
        verify(redisLockService, never()).tryLock(anyLong());
        verify(eventSeatRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void createHoldWithSeatAlreadyBookedIsRejectedWithDistinctCodeFromHeld() {
        HoldService service = newHoldService();

        when(redisLockService.tryLock(1L)).thenReturn(Optional.of("token-1"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(holdRepository.save(any(Hold.class))).thenAnswer(invocation -> {
            Hold hold = invocation.getArgument(0);
            hold.setId(101L);
            return hold;
        });

        EventSeat bookedSeat = EventSeat.builder()
                .id(1L)
                .status(EventSeatStatus.BOOKED)
                .price(new BigDecimal("50.00"))
                .build();
        when(eventSeatRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(bookedSeat));

        assertThatThrownBy(() -> service.createHold(1L, new HoldRequest(List.of(1L))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiException.getCode()).isEqualTo("SEAT_ALREADY_BOOKED");
                });

        verify(redisLockService, times(1)).unlock(1L, "token-1");
        verify(holdSeatRepository, never()).saveAll(any());
    }

    @Test
    void createHoldWithRedisContentionOnOneSeatReleasesAllPreviouslyAcquiredLocks() {
        HoldService service = newHoldService();

        when(redisLockService.tryLock(1L)).thenReturn(Optional.of("token-1"));
        when(redisLockService.tryLock(2L)).thenReturn(Optional.of("token-2"));
        when(redisLockService.tryLock(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createHold(1L, new HoldRequest(List.of(3L, 1L, 2L))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiException.getCode()).isEqualTo("SEAT_ALREADY_HELD");
                });

        // Seat ids are deduped/sorted ascending before locking, so 1 and 2 are
        // acquired before contention is hit on 3 - both must be released.
        verify(redisLockService, times(1)).unlock(1L, "token-1");
        verify(redisLockService, times(1)).unlock(2L, "token-2");
        verify(redisLockService, never()).unlock(eq(3L), any());
        verify(eventSeatRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void createHoldWithSeatAlreadyHeldAtPostgresLockStageIsRejected() {
        HoldService service = newHoldService();

        when(redisLockService.tryLock(1L)).thenReturn(Optional.of("token-1"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(holdRepository.save(any(Hold.class))).thenAnswer(invocation -> {
            Hold hold = invocation.getArgument(0);
            hold.setId(100L);
            return hold;
        });

        Hold activeStaleHold = Hold.builder()
                .id(50L)
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        EventSeat heldSeat = EventSeat.builder()
                .id(1L)
                .status(EventSeatStatus.HELD)
                .price(new BigDecimal("50.00"))
                .currentHold(activeStaleHold)
                .build();
        when(eventSeatRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(heldSeat));

        assertThatThrownBy(() -> service.createHold(1L, new HoldRequest(List.of(1L))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiException.getCode()).isEqualTo("SEAT_ALREADY_HELD");
                });

        verify(redisLockService, times(1)).unlock(1L, "token-1");
        verify(holdSeatRepository, never()).saveAll(any());
    }

    @Test
    void releaseHoldWithMissingHoldIsNotFound() {
        HoldService service = newHoldService();
        when(holdRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.releaseHold(1L, 999L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiException.getCode()).isEqualTo("HOLD_NOT_FOUND");
                });
    }

    @Test
    void releaseHoldOwnedBySomeoneElseIsNotFound() {
        HoldService service = newHoldService();
        Hold othersHold = Hold.builder()
                .id(10L)
                .user(User.builder().id(2L).build())
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(othersHold));

        assertThatThrownBy(() -> service.releaseHold(1L, 10L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiException.getCode()).isEqualTo("HOLD_NOT_FOUND");
                });
    }

    @Test
    void releaseAlreadyNonActiveHoldIsRejected() {
        HoldService service = newHoldService();
        Hold expiredHold = Hold.builder()
                .id(11L)
                .user(User.builder().id(1L).build())
                .status(HoldStatus.EXPIRED)
                .expiresAt(Instant.now().minusSeconds(60))
                .build();
        when(holdRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(expiredHold));

        assertThatThrownBy(() -> service.releaseHold(1L, 11L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    // Stored status is EXPIRED - ADR-0009's split keys HOLD_EXPIRED
                    // vs HOLD_NOT_ACTIVE on domain state, not on which check fired.
                    assertThat(apiException.getCode()).isEqualTo("HOLD_EXPIRED");
                });
    }

    @Test
    void releaseHoldFlipsOwnedSeatsBackToAvailable() {
        HoldService service = newHoldService();
        Hold ownedHold = Hold.builder()
                .id(30L)
                .user(User.builder().id(1L).build())
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(holdRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(ownedHold));

        EventSeat seat = EventSeat.builder()
                .id(6L)
                .status(EventSeatStatus.HELD)
                .price(new BigDecimal("50.00"))
                .currentHold(ownedHold)
                .build();
        when(eventSeatRepository.findIdsByCurrentHoldId(30L)).thenReturn(List.of(seat.getId()));
        when(eventSeatRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(seat));

        service.releaseHold(1L, 30L);

        assertThat(seat.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(seat.getCurrentHold()).isNull();
        assertThat(ownedHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
    }

    /**
     * Regression test for the race a code review caught: releaseHold must
     * re-verify seat ownership under the seat's own row lock
     * ({@code findByIdForUpdate}), not trust the unlocked candidate list.
     * Here the seat has already been reassigned to a different hold by the
     * time the row lock is taken - releaseHold must leave it untouched rather
     * than clobbering the new owner.
     *
     * <p><b>This test cannot see the bug that actually mattered here, and is
     * kept only for the branch coverage.</b> It passed unchanged for the
     * whole life of the T-006 defect: it stubs {@code findByIdForUpdate} to
     * hand back a seat carrying the new owner, which is precisely what the
     * production code could <em>not</em> get, because the candidate lookup
     * had already put its own pre-lock snapshot of that seat in the
     * persistence context and Hibernate's identity map returned that instead
     * (ADR-0010). A mock has no identity map, so the one thing that broke is
     * the one thing it cannot express. {@code
     * HoldReleaseSeatLockRaceIntegrationTest} is the test that fails when
     * this regresses.
     */
    @Test
    void releaseHoldDoesNotClobberASeatReassignedToADifferentHoldSinceCandidateListWasRead() {
        HoldService service = newHoldService();
        Hold ownedHold = Hold.builder()
                .id(20L)
                .user(User.builder().id(1L).build())
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(holdRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(ownedHold));

        Hold reassignedHold = Hold.builder()
                .id(21L)
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        EventSeat seat = EventSeat.builder()
                .id(5L)
                .status(EventSeatStatus.HELD)
                .price(new BigDecimal("50.00"))
                .currentHold(reassignedHold)
                .build();
        when(eventSeatRepository.findIdsByCurrentHoldId(20L)).thenReturn(List.of(seat.getId()));
        when(eventSeatRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(seat));

        service.releaseHold(1L, 20L);

        assertThat(seat.getStatus()).isEqualTo(EventSeatStatus.HELD);
        assertThat(seat.getCurrentHold()).isEqualTo(reassignedHold);
        assertThat(ownedHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
    }
}
