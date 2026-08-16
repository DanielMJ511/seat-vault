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
 * The test waits for that park to be observable in {@code pg_locks}, scoped
 * to the releasing thread's own backend pid (see {@link
 * #awaitReleaseBlockedOnTheSeatRow}), and asserts the release really has not
 * finished before the thief commits, so there is no sleep-and-hope window in
 * which the release could slip through and produce a false pass.
 *
 * <p><b>The thief transaction is manufactured, and says so.</b> It is not a
 * replay of a production sequence: it frees the seat with the sweep's own
 * two {@code event_seats}-only statements ({@link
 * EventSeatRepository#findIdsOfExpiredHeldSeatsForUpdate} then {@link
 * EventSeatRepository#releaseExpiredHeldSeats}) — and then creates a genuine new
 * hold through {@link HoldService#createHold}, deliberately splitting apart
 * two things no single production path does in that combination, and
 * bypassing {@code HoldSweepService} rather than waiting for it. What it
 * models is the <em>shape</em> of a competitor: some other transaction
 * re-homes this seat onto a different hold and commits, between the moment
 * {@code releaseHold} reads its candidate list and the moment it takes the
 * seat's row lock. This test is a forward-looking guard on ADR-0010's
 * discipline, held to the standard ADR-0010 sets for itself when it calls the
 * masking "an accident of the current call graph, not a guarantee."
 *
 * <p><b>Why it has to be manufactured.</b> The natural competitor is {@code
 * createHold}'s lazy-expiry reconciliation of the <em>outgoing</em> hold
 * (ADR-0002). Until T-007 it could not be used at all: {@code releaseHold}
 * locked the {@code holds} row first and held it for its whole transaction,
 * so that path could not commit underneath a release, it deadlocked with it
 * — which is the bug T-007 then fixed by inverting the order. It still
 * cannot be used, for a different and less alarming reason: reconciling the
 * outgoing hold sets it {@code EXPIRED}, so a release arriving afterwards
 * stops at its status-not-ACTIVE check (which now runs after the seat locks,
 * and reports {@code HOLD_EXPIRED} per ADR-0009's domain-state-keyed split -
 * see {@link HoldExpiry}) and never reaches the per-seat ownership guard this
 * test exists to exercise. Freeing the seat without touching its hold's row
 * is the only way to drive the release all the way into that guard.
 *
 * <p><b>What T-007 changed here.</b> {@code releaseHold} no longer pins the
 * {@code holds} row for the duration, so the guard is no longer dead code
 * that a lock happens to protect — a competitor can now genuinely commit a
 * re-homing while a release is parked on an earlier seat, exactly as this
 * test stages. The ADR-0010 projection is what makes the guard see it.
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
        AtomicLong releasePid = new AtomicLong();
        AtomicLong thiefHoldId = new AtomicLong();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // First statements, so the seat row is locked before the
                // releasing thread can possibly reach it. Mirrors
                // HoldSweepService#sweepExpiredHolds's own two-statement
                // shape (ADR-0011's seat-versus-seat ordering) rather than
                // calling the bulk UPDATE directly.
                Instant now = Instant.now();
                List<Long> expiredSeatIds = eventSeatRepository.findIdsOfExpiredHeldSeatsForUpdate(now);
                eventSeatRepository.releaseExpiredHeldSeats(expiredSeatIds, now);

                release.set(executor.submit(() -> {
                    try {
                        transactionTemplate.executeWithoutResult(releaseStatus -> {
                            // Published before any locking so the poll below
                            // can be scoped to this exact backend. Wrapping
                            // the call in a template rather than letting its
                            // own @Transactional start the transaction is the
                            // only way to learn the backend pid from outside;
                            // propagation is REQUIRED, so releaseHold joins
                            // this transaction and behaves identically.
                            releasePid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Long.class));
                            holdService.releaseHold(ownerId, ownerHoldId);
                        });
                    } catch (Throwable t) {
                        releaseFailure.set(t);
                    } finally {
                        releaseFinished.set(true);
                    }
                }));

                awaitReleaseBlockedOnTheSeatRow(releasePid);
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
     * Waits until the releasing thread is parked on an {@code event_seats} row
     * lock - i.e. inside {@code findByIdForUpdate}, past its unlocked
     * candidate read and short of its ownership guard, which is precisely the
     * window under test. Fails loudly rather than proceeding on a timeout: a
     * release that never parked would sail through and turn this into a test
     * that passes for the wrong reason.
     *
     * <p>Scoped to that thread's own backend pid, and to the table. It used to
     * be an unscoped {@code select count(*) from pg_locks where not granted},
     * which a code review flagged: {@code HoldSweepService} runs every 30
     * seconds, {@code @EnableScheduling} is not gated by profile, Spring's
     * context cache keeps that scheduler alive for the whole suite, and this
     * test deliberately creates exactly the expired-hold rows the sweep hunts
     * for. A sweep blocked on the very seat row the enclosing transaction
     * holds would have satisfied the unscoped poll and released the thief
     * early - a false pass, and a contradiction of this test's claim to have
     * no sleep-and-hope window.
     *
     * <p>The join looks indirect because of how Postgres represents a row-lock
     * wait: the ungranted entry is a {@code ShareLock} on the holder's {@code
     * transactionid} and carries no relation at all, so the relation has to be
     * read off the {@code tuple} lock the waiter holds while waiting.
     * (Observed against Postgres before being written, not guessed.)
     */
    private void awaitReleaseBlockedOnTheSeatRow(AtomicLong releasePid) {
        Instant deadline = Instant.now().plus(BLOCK_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            long backend = releasePid.get();
            if (backend != 0) {
                Integer waiting = jdbcTemplate.queryForObject("""
                        select count(*)
                        from pg_locks waiting
                        join pg_locks tuple_lock
                          on tuple_lock.pid = waiting.pid
                         and tuple_lock.locktype = 'tuple'
                         and tuple_lock.relation = 'event_seats'::regclass
                        where waiting.pid = ?
                          and not waiting.granted
                        """, Integer.class, backend);
                if (waiting != null && waiting > 0) {
                    return;
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("The releasing thread (backend pid " + releasePid.get() + ") was not blocked on an "
                + "event_seats row lock within " + BLOCK_TIMEOUT + "; it never reached findByIdForUpdate while the "
                + "seat row was held, so this run proves nothing.");
    }

    private long userId(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
    }
}
