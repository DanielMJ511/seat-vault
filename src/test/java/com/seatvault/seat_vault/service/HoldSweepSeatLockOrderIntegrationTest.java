package com.seatvault.seat_vault.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.dto.HoldRequest;
import com.seatvault.seat_vault.entity.Event;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins the seat-versus-seat residual T-001/#16 closed: {@code
 * HoldSweepService#sweepExpiredHolds} used to lock the {@code event_seats}
 * rows its bulk {@code UPDATE} matched in whatever order Postgres's plan
 * produced them, not the ascending-id order every other multi-seat path uses
 * (see ADR-0011's "The seat-versus-seat residual, closed in M8 (#16)"). A
 * concurrent multi-seat {@code createHold} locking the same two seats in the
 * other order could then deadlock with the sweep - the same user-visible
 * symptom {@link HoldLockOrderDeadlockIntegrationTest} pins for the
 * table-versus-table case, but between two {@code event_seats} rows rather
 * than between {@code event_seats} and {@code holds}.
 *
 * <p><b>This test asserts lock order directly, not the deadlock it can
 * cause.</b> A deadlock is a <em>consequence</em> of two sides locking two
 * resources in opposite orders; {@link HoldLockOrderDeadlockIntegrationTest}
 * stages that four-way choreography for the table-versus-table bug. Here
 * there is only one ordering property to check - does the sweep touch the
 * low-id seat before the high-id seat - and asserting it directly, with one
 * parked transaction and one background thread, is far more deterministic
 * than manufacturing a second deadlocking party.
 *
 * <p><b>How "the sweep holds no lock on the high-id seat" is actually
 * observed.</b> Postgres does not register an ordinary, <em>uncontested</em>
 * row lock in {@code pg_locks} at all - confirmed against real Postgres: a
 * bare {@code SELECT ... FOR UPDATE} with nothing else touching that row
 * produces zero {@code pg_locks} rows for it, because the row's lock lives in
 * its {@code xmax}, not in the lock manager. Only a <em>wait</em> for a lock
 * shows up there. So "the sweep holds no lock on the high-id seat" cannot be
 * read directly off a passive {@code pg_locks} query while nothing else is
 * contending for that row; it has to be made observable by having a third,
 * independent transaction attempt to take a conflicting lock on it with
 * {@code NOWAIT} and checking whether Postgres grants it immediately or
 * refuses because somebody already holds it ({@link #canLockImmediately}).
 * Confirming the sweep itself is genuinely parked on the low-id seat first
 * still uses the same {@code pg_locks}-driven mechanics as {@link
 * HoldLockOrderDeadlockIntegrationTest#awaitBlockedOnRowLockIn} (reused here
 * as {@link #awaitBlockedOnRowLockIn}).
 *
 * <p><b>Forcing the plan to visit the high-id seat before the low-id
 * one.</b> A freshly-created two-seat hold (the ordinary case: {@code
 * HoldService#createHold} locks and updates its seats in ascending id order)
 * leaves the low-id seat's row physically <em>before</em> the high-id seat's
 * row, so an unordered scan usually visits them in the safe order anyway and
 * would make this test pass for the wrong reason. A row that gets {@code
 * UPDATE}d moves to a later physical position, so repeatedly touching the
 * low-id seat (a no-op status rewrite) walks it past the high-id seat's
 * physical position. Verified against real Postgres, including at a
 * realistic table size (~3000 background rows): a <em>single</em> touch is
 * not reliably enough - an update often lands back on the same page - so
 * {@link #forceUnorderedScanToVisitHighBeforeLow()} touches repeatedly and
 * re-checks after each one, and fails loudly, naming its own premise, if the
 * physical layout never flips within a generous bound. That loop is this
 * test's premise self-check: if a future Postgres version changes how
 * {@code UPDATE} places new tuple versions, or how the planner orders ties
 * for equal {@code current_hold_id} values, this fails with an explanation
 * rather than silently starting to pass for the wrong reason.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class HoldSweepSeatLockOrderIntegrationTest {

    private static final String OWNER_EMAIL = "alice@example.com";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_REORDER_ATTEMPTS = 1000;

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldSweepService holdSweepService;

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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long lowSeatId;
    private long highSeatId;
    private long holdId;

    /**
     * Builds a private venue/event/two seats rather than borrowing the
     * seeded grid, for the same reason {@link HoldLockOrderDeadlockIntegrationTest}
     * does: this test is not {@code @Transactional}, so everything it does
     * commits for real, and a shared fixture would leak between runs.
     */
    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("Sweep-Lock-Order Venue " + System.nanoTime())
                .address("1 Test Way")
                .build());
        Event event = eventRepository.save(Event.builder()
                .venue(venue)
                .name("Sweep-Lock-Order Event")
                .startsAt(Instant.now().plus(400, ChronoUnit.DAYS))
                .build());
        lowSeatId = newEventSeat(venue, event, 1);
        highSeatId = newEventSeat(venue, event, 2);
        assertThat(lowSeatId).isLessThan(highSeatId);

        holdId = holdService.createHold(userId(OWNER_EMAIL), new HoldRequest(List.of(lowSeatId, highSeatId)))
                .id();
        expireHoldInItsOwnTransaction();

        forceUnorderedScanToVisitHighBeforeLow();
    }

    @Test
    void sweepLocksLowIdSeatBeforeHighIdSeat() throws Exception {
        AtomicLong sweepPid = new AtomicLong();
        AtomicReference<Throwable> sweepFailure = new AtomicReference<>();
        AtomicBoolean sweepFinished = new AtomicBoolean();
        AtomicReference<Future<?>> sweep = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // First statement, so the low-id seat is pinned before the
                // sweep can possibly reach it.
                eventSeatRepository.findByIdForUpdate(lowSeatId).orElseThrow();

                sweep.set(executor.submit(() -> runInTransaction(sweepPid, sweepFailure, sweepFinished,
                        holdSweepService::sweepExpiredHolds)));
                awaitBlockedOnRowLockIn(sweepPid, "event_seats", "the sweep", sweepFinished);
                assertThat(sweepFinished)
                        .as("the sweep must still be parked on the low-id seat's row lock, not already "
                                + "committed")
                        .isFalse();

                // The crux of the fix: while the sweep is blocked trying to
                // lock the low-id seat, an independent transaction must
                // still be able to lock the high-id seat immediately.
                // Pre-fix, the sweep's single unordered bulk UPDATE had
                // already locked the high-id seat before it ever reached the
                // low-id one, so this would fail.
                assertThat(canLockImmediately(highSeatId))
                        .as("the sweep must not hold a lock on the high-id seat while still blocked on the "
                                + "low-id seat - pre-fix, its unordered bulk UPDATE locks matched rows in "
                                + "plan order and reaches the high-id seat first")
                        .isTrue();
            });

            sweep.get().get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(sweepFailure.get()).isNull();

        EventSeat low = eventSeatRepository.findById(lowSeatId).orElseThrow();
        assertThat(low.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(low.getCurrentHold()).isNull();

        EventSeat high = eventSeatRepository.findById(highSeatId).orElseThrow();
        assertThat(high.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(high.getCurrentHold()).isNull();

        assertThat(holdRepository.findById(holdId).orElseThrow().getStatus()).isEqualTo(HoldStatus.EXPIRED);
    }

    /**
     * Repeatedly rewrites the low-id seat's own {@code status} column
     * (a no-op value-wise, since it is already {@code HELD}) until the
     * unordered predicate scan visits the high-id seat before it - the setup
     * this test's premise depends on. Fails loudly, naming the premise by
     * name, rather than silently proceeding to assert something that can no
     * longer fail: see this class's Javadoc for why a single touch is not
     * enough at realistic table sizes.
     */
    private void forceUnorderedScanToVisitHighBeforeLow() {
        for (int attempt = 0; attempt < MAX_REORDER_ATTEMPTS; attempt++) {
            if (unorderedScanVisitsHighBeforeLow()) {
                return;
            }
            jdbcTemplate.update("update event_seats set status = status where id = ?", lowSeatId);
        }
        throw new AssertionError("this test's premise no longer holds: could not force the unordered scan "
                + "that HoldSweepService's pre-fix bulk UPDATE relied on to visit the high-id seat ("
                + highSeatId + ") before the low-id seat (" + lowSeatId + ") after " + MAX_REORDER_ATTEMPTS
                + " touches - Postgres's planner, storage layout, or update-in-place behaviour may have "
                + "changed enough that this lever no longer works; find a shape that does rather than "
                + "trusting this test.");
    }

    /**
     * Mirrors the predicate {@code HoldSweepService}'s pre-fix bulk
     * {@code UPDATE} used (status {@code HELD}, hold past its expiry),
     * scoped to this test's own hold so a busy shared schema can't produce a
     * false reading either way. This is a premise check only - the actual
     * regression assertion is {@link #canLockImmediately}, exercised against
     * the real {@code HoldSweepService} call.
     */
    private boolean unorderedScanVisitsHighBeforeLow() {
        List<Long> order = jdbcTemplate.queryForList(
                """
                select es.id from event_seats es
                join holds h on h.id = es.current_hold_id
                where es.status = 'HELD'
                  and h.expires_at < now()
                  and es.current_hold_id = ?
                """,
                Long.class, holdId);
        return order.equals(List.of(highSeatId, lowSeatId));
    }

    /**
     * Attempts to take Postgres's row-share lock on {@code seatId} with
     * {@code NOWAIT} and reports whether it succeeded. This is the only
     * reliable way to ask "is this row currently locked by some other
     * transaction?" from the outside: an uncontested lock leaves no trace in
     * {@code pg_locks} (see this class's Javadoc), so probing has to attempt
     * the conflicting lock itself rather than read for one.
     */
    private boolean canLockImmediately(long seatId) {
        try {
            jdbcTemplate.queryForObject("select id from event_seats where id = ? for update nowait", Long.class,
                    seatId);
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    private void runInTransaction(AtomicLong pidSink, AtomicReference<Throwable> failureSink,
            AtomicBoolean finishedFlag, Runnable body) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // Published before any locking so the coordinating thread
                // can scope its pg_locks poll to this exact backend.
                pidSink.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Long.class));
                body.run();
            });
        } catch (Throwable t) {
            failureSink.set(t);
        } finally {
            finishedFlag.set(true);
        }
    }

    private void expireHoldInItsOwnTransaction() {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(
                status -> holdRepository.findById(holdId).orElseThrow().setExpiresAt(Instant.now().minusSeconds(120)));
    }

    /**
     * Waits until the given backend is genuinely parked on a row lock in the
     * given table. Scoped by PID and by relation for the same reason {@link
     * HoldLockOrderDeadlockIntegrationTest#awaitBlockedOnRowLockIn} is: an
     * unscoped poll could be satisfied by any other lock holder in this
     * database rather than the one this test is driving - this class's own
     * {@code sweepExpiredHolds()} call being the most immediate example, since
     * it locks {@code event_seats} rows directly under test here. (Historical
     * note: before T-001 gated it off under the {@code test} profile, {@code
     * HoldSweepService}'s own 30-second job was also live in this shared
     * Spring context and was the original reason this scoping was added; see
     * {@code config/SchedulingConfig.java}.)
     */
    private void awaitBlockedOnRowLockIn(AtomicLong pid, String relation, String who, AtomicBoolean finishedEarly) {
        Instant deadline = Instant.now().plus(BLOCK_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (finishedEarly.get()) {
                throw new AssertionError(
                        who + " finished before ever parking on a " + relation + " row lock, so this run proves "
                                + "nothing.");
            }
            long backend = pid.get();
            if (backend != 0) {
                Integer waiting = jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from pg_locks waiting
                        join pg_locks tuple_lock
                          on tuple_lock.pid = waiting.pid
                         and tuple_lock.locktype = 'tuple'
                         and tuple_lock.relation = ?::regclass
                        where waiting.pid = ?
                          and not waiting.granted
                        """,
                        Integer.class, relation, backend);
                if (waiting != null && waiting > 0) {
                    return;
                }
            }
            sleep();
        }
        throw new AssertionError(who + " (backend pid " + pid.get() + ") was not parked on a " + relation
                + " row lock within " + BLOCK_TIMEOUT + ", so this run proves nothing.");
    }

    private static void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long newEventSeat(Venue venue, Event event, int seatNumber) {
        Seat seat = seatRepository.save(Seat.builder()
                .venue(venue)
                .section("A")
                .rowLabel("A")
                .seatNumber(seatNumber)
                .build());
        return eventSeatRepository
                .save(EventSeat.builder()
                        .event(event)
                        .seat(seat)
                        .status(EventSeatStatus.AVAILABLE)
                        .price(new BigDecimal("40.00"))
                        .build())
                .getId();
    }

    private long userId(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
    }
}
