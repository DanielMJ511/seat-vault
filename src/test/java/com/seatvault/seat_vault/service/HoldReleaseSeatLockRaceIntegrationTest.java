package com.seatvault.seat_vault.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.dto.HoldRequest;
import com.seatvault.seat_vault.entity.Event;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import com.seatvault.seat_vault.entity.Hold;
import com.seatvault.seat_vault.entity.HoldStatus;
import com.seatvault.seat_vault.entity.Seat;
import com.seatvault.seat_vault.entity.Venue;
import com.seatvault.seat_vault.repository.EventRepository;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.HoldRepository;
import com.seatvault.seat_vault.repository.SeatRepository;
import com.seatvault.seat_vault.repository.UserRepository;
import com.seatvault.seat_vault.repository.VenueRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins the defect T-006 fixed: {@code HoldService#releaseHold}'s per-seat
 * ownership guard used to run against a <em>stale</em> {@code EventSeat}
 * snapshot, so the Postgres row lock it was written to rely on (ADR-0001)
 * protected nothing.
 *
 * <p><b>The mechanism (ADR-0010).</b> {@code releaseHold} gathers its
 * candidate seats with an unlocked read, then re-reads each one under {@code
 * findByIdForUpdate} before mutating it. When that unlocked read returns
 * managed {@code EventSeat} <em>entities</em> (as the old {@code
 * findByCurrentHoldId} did), Hibernate's identity map hands the later,
 * correctly-locked read back the instance it already has for that id instead
 * of the freshly-locked row. Postgres really does take the lock; the
 * application then decides using data from before it. The fix is {@link
 * EventSeatRepository#findIdsByCurrentHoldId}, a scalar id projection that
 * puts nothing in the persistence context.
 *
 * <p><b>Why this needs a real database.</b> {@code HoldServiceTest#
 * releaseHoldDoesNotClobberASeatReassignedToADifferentHoldSinceCandidateListWasRead}
 * asserts exactly the behaviour under test here and passed throughout the
 * bug's lifetime: a Mockito stub returns whatever the test tells it to for
 * {@code findByIdForUpdate}, so it can express "the row changed underneath
 * us" but cannot reproduce the identity map that made the production code
 * unable to see it. Only a real session against a real row can.
 *
 * <p><b>How the interleaving is made deterministic.</b> The "thief"
 * transaction runs on the test thread and takes the seat's row lock with its
 * first statement, so the releasing thread is guaranteed to park at {@code
 * findByIdForUpdate} — after its unlocked candidate read, before its guard.
 * The test waits for that park to be observable in {@code pg_locks} (and
 * asserts the release really has not finished) before the thief commits, so
 * there is no sleep-and-hope window in which the release could slip through
 * and produce a false pass.
 *
 * <p><b>What the thief models, and one thing it deliberately does not.</b>
 * The thief frees the seat with {@link
 * EventSeatRepository#releaseExpiredHeldSeats} — the sweep's own statement,
 * which touches {@code event_seats} only — and then creates a genuine new
 * hold through {@link HoldService#createHold}. It does <em>not</em> reach the
 * seat through {@code createHold}'s lazy-expiry reconciliation of the
 * <em>outgoing</em> hold (ADR-0002), because that path writes the outgoing
 * {@code holds} row, which {@code releaseHold} has locked for its whole
 * transaction ({@code HoldService.java:163}) — so it cannot commit
 * underneath a release; it can only queue behind it. That hold-row lock is
 * what keeps this defect latent in today's arrangement rather than routinely
 * exploitable, and it is not something to rely on: it is one lock in one
 * method away from being false, the guard it accidentally protects is dead
 * code as long as it holds, and any new path that re-homes a seat without
 * writing its hold's row (a sweep split for throughput, an admin seat move,
 * a hold merge) makes the clobber live with no other code change. Hence
 * ADR-0010 as a discipline, and hence this test.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class HoldReleaseSeatLockRaceIntegrationTest {

    private static final String OWNER_EMAIL = "alice@example.com";
    private static final String THIEF_EMAIL = "bob@example.com";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private HoldService holdService;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventSeatRepository eventSeatRepository;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long seatId;
    private long ownerHoldId;

    /**
     * Builds a private venue/event/seat rather than borrowing the seeded
     * grid: this test is not {@code @Transactional} (every participant needs
     * its own real transaction for {@code SELECT ... FOR UPDATE} to mean
     * anything), so everything it does commits for real.
     */
    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("Release-Race Venue " + System.nanoTime())
                .address("1 Test Way")
                .build());
        Seat seat = seatRepository.save(Seat.builder()
                .venue(venue)
                .section("A")
                .rowLabel("A")
                .seatNumber(1)
                .build());
        Event event = eventRepository.save(Event.builder()
                .venue(venue)
                .name("Release-Race Event")
                .startsAt(Instant.now().plus(400, ChronoUnit.DAYS))
                .build());
        seatId = eventSeatRepository.save(EventSeat.builder()
                .event(event)
                .seat(seat)
                .status(EventSeatStatus.AVAILABLE)
                .price(new BigDecimal("40.00"))
                .build())
                .getId();

        ownerHoldId = holdService.createHold(userId(OWNER_EMAIL), new HoldRequest(List.of(seatId))).id();
        // Past its TTL but still stored ACTIVE - the ordinary state of any
        // hold the sweep hasn't reached yet (ADR-0002), and the state in
        // which a seat can legitimately change hands under a release.
        transactionTemplate.executeWithoutResult(status ->
                holdRepository.findById(ownerHoldId).orElseThrow().setExpiresAt(Instant.now().minusSeconds(120)));
    }

    @Test
    void releaseMustNotClobberASeatThatChangedHandsAfterTheCandidateListWasRead() throws Exception {
        long ownerId = userId(OWNER_EMAIL);
        long thiefId = userId(THIEF_EMAIL);
        AtomicReference<Future<?>> release = new AtomicReference<>();
        AtomicReference<Throwable> releaseFailure = new AtomicReference<>();
        AtomicBoolean releaseFinished = new AtomicBoolean();
        AtomicLong thiefHoldId = new AtomicLong();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // First statement, so the seat row is locked before the
                // releasing thread can possibly reach it.
                eventSeatRepository.releaseExpiredHeldSeats(Instant.now());

                release.set(executor.submit(() -> {
                    try {
                        holdService.releaseHold(ownerId, ownerHoldId);
                    } catch (Throwable t) {
                        releaseFailure.set(t);
                    } finally {
                        releaseFinished.set(true);
                    }
                }));

                awaitBlockedOnARowLock();
                assertThat(releaseFinished)
                        .as("the release must still be parked on the seat row lock, not already committed")
                        .isFalse();

                thiefHoldId.set(holdService.createHold(thiefId, new HoldRequest(List.of(seatId))).id());
            });

            release.get().get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(releaseFailure.get()).isNull();

        EventSeat reloaded = eventSeatRepository.findById(seatId).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("the seat belongs to the thief's live hold; the release must have left it alone")
                .isEqualTo(EventSeatStatus.HELD);
        assertThat(reloaded.getCurrentHold())
                .as("the release cleared a hold pointer it no longer owned")
                .isNotNull();
        assertThat(reloaded.getCurrentHold().getId()).isEqualTo(thiefHoldId.get());

        assertThat(holdRepository.findById(thiefHoldId.get()).orElseThrow().getStatus())
                .isEqualTo(HoldStatus.ACTIVE);
        // The release still does its own job: the released hold is EXPIRED
        // (ADR-0007), it just no longer owns the seat to give back.
        assertThat(holdRepository.findById(ownerHoldId).orElseThrow().getStatus())
                .isEqualTo(HoldStatus.EXPIRED);
    }

    /**
     * Waits until some session is genuinely waiting on a lock. The only
     * contended lock in play is the seat row the enclosing transaction holds,
     * and the only other session is the releasing thread, so an ungranted
     * lock here means that thread is parked inside {@code findByIdForUpdate}
     * - past its unlocked candidate read, short of its ownership guard, which
     * is precisely the window under test. Fails loudly rather than proceeding
     * on a timeout: a release that never parked would sail through and turn
     * this into a test that passes for the wrong reason.
     */
    private void awaitBlockedOnARowLock() {
        Instant deadline = Instant.now().plus(BLOCK_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_locks where not granted", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError(
                "No session blocked on a row lock within " + BLOCK_TIMEOUT + "; the release never reached "
                        + "findByIdForUpdate while the seat row was held, so this run proves nothing.");
    }

    private long userId(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
    }
}
