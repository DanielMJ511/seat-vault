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
import com.seatvault.seat_vault.exception.ApiException;
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
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins the defect T-007 fixed: {@code HoldService} used to acquire the {@code
 * holds} and {@code event_seats} row locks in <em>opposite</em> orders in
 * different methods, so a legitimate release racing a legitimate create
 * deadlocked in Postgres and one of the two users got a 500.
 *
 * <p><b>The two orders.</b> {@code releaseHold} (and {@code
 * BookingService#createFromHold}) locked the {@code holds} row first, then
 * each {@code event_seats} row. {@code createHold} (and {@code
 * HoldSweepService#sweepExpiredHolds}) do the reverse. The second lock on the
 * seats-first side is <em>invisible at the call site</em>: nothing in {@code
 * createHold} reads like a lock acquisition, it is Hibernate flushing the
 * stale hold that the lazy-expiry reconciliation (ADR-0002) dirtied, and an
 * UPDATE takes the row lock just as surely as {@code SELECT ... FOR UPDATE}
 * does. The fix makes every path that touches both tables take {@code
 * event_seats} first (ascending id) and {@code holds} second - see ADR-0011.
 *
 * <p><b>How the interleaving is made deterministic.</b> An ABBA deadlock needs
 * each side to be holding one resource and waiting for the other, so both
 * sides have to be parked mid-transaction at a known point. Two seats and one
 * test-owned transaction are enough:
 *
 * <ol>
 *   <li>the test transaction takes seat 1's row lock and keeps it;</li>
 *   <li>the releasing thread starts, and parks on seat 1 - pre-fix it is
 *       already holding the {@code holds} row when it parks, which is the
 *       whole defect;</li>
 *   <li>the creating thread starts on seat 2, which nobody holds. Pre-fix it
 *       takes seat 2 and then parks trying to flush the stale hold's UPDATE.
 *       Post-fix nothing owns the {@code holds} row yet, so it simply
 *       finishes;</li>
 *   <li>the test transaction commits. Pre-fix the release now wakes, takes
 *       seat 1, asks for seat 2 - which the creator holds while waiting for
 *       the release's own {@code holds} row - and Postgres reports {@code
 *       deadlock detected}, killing one of the two.</li>
 * </ol>
 *
 * Every step waits for an observable lock state rather than sleeping, and each
 * wait is scoped to the exact backend PID of the thread it is waiting for (see
 * {@link #awaitBlockedOnRowLockIn}), so nothing else running in the shared
 * Spring context - {@code HoldSweepService}'s 30-second job in particular -
 * can satisfy a wait on the wrong session's behalf.
 *
 * <p><b>What it asserts.</b> Not "something completed": that both threads
 * finish with either success or a deliberate {@link ApiException}, and
 * specifically never with a lock/deadlock failure. Any other exception reaches
 * {@code GlobalExceptionHandler}'s catch-all and becomes a 500 on a request
 * that did nothing wrong, which is exactly the user-visible symptom of this
 * bug and a direct failure of ADR-0001's guarantee.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class HoldLockOrderDeadlockIntegrationTest {

    private static final String OWNER_EMAIL = "alice@example.com";
    private static final String RIVAL_EMAIL = "bob@example.com";
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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long parkedSeatId;
    private long contestedSeatId;
    private long ownerHoldId;

    /**
     * Builds a private venue/event/two seats rather than borrowing the seeded
     * grid: this test is not {@code @Transactional} (every participant needs
     * its own real transaction for row locks to mean anything), so everything
     * it does commits for real. Two seats are required - one to park the
     * releasing thread on, one for the two threads to fight over - because a
     * deadlock cycle needs both sides holding something.
     */
    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("Lock-Order Venue " + System.nanoTime())
                .address("1 Test Way")
                .build());
        Event event = eventRepository.save(Event.builder()
                .venue(venue)
                .name("Lock-Order Event")
                .startsAt(Instant.now().plus(400, ChronoUnit.DAYS))
                .build());
        parkedSeatId = newEventSeat(venue, event, 1);
        contestedSeatId = newEventSeat(venue, event, 2);
        // Ascending id order is what every locking loop uses, so the release
        // is guaranteed to reach the parked seat before the contested one.
        assertThat(parkedSeatId).isLessThan(contestedSeatId);

        ownerHoldId = holdService.createHold(userId(OWNER_EMAIL),
                new HoldRequest(List.of(parkedSeatId, contestedSeatId))).id();
    }

    @Test
    void releaseRacingCreateOnTheSameHoldMustNotDeadlock() throws Exception {
        long ownerId = userId(OWNER_EMAIL);
        long rivalId = userId(RIVAL_EMAIL);

        AtomicLong releasePid = new AtomicLong();
        AtomicReference<Throwable> releaseFailure = new AtomicReference<>();
        AtomicBoolean releaseFinished = new AtomicBoolean();

        AtomicLong createPid = new AtomicLong();
        AtomicReference<Throwable> createFailure = new AtomicReference<>();
        AtomicBoolean createFinished = new AtomicBoolean();
        AtomicLong rivalHoldId = new AtomicLong();

        AtomicReference<Future<?>> release = new AtomicReference<>();
        AtomicReference<Future<?>> create = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // First statement, so the parked seat is pinned before either
                // thread can reach it.
                eventSeatRepository.findByIdForUpdate(parkedSeatId).orElseThrow();

                // Only now is the hold pushed past its TTL - the ordinary
                // state of any hold the sweep hasn't reached yet (ADR-0002),
                // and the state in which a seat can legitimately change hands
                // under a release. Doing it here, in its own committed
                // transaction and after the parked seat is pinned, leaves
                // HoldSweepService no window: any sweep that starts from now
                // on has to queue behind the lock this transaction holds, and
                // any sweep that ran before this saw a hold that was still
                // live and skipped it.
                expireOwnerHoldInItsOwnTransaction();

                release.set(executor.submit(() -> runInTransaction(releasePid, releaseFailure, releaseFinished,
                        () -> holdService.releaseHold(ownerId, ownerHoldId))));
                awaitBlockedOnRowLockIn(releasePid, "event_seats", "the releasing thread", null);
                assertThat(releaseFinished)
                        .as("the release must still be parked on the parked seat's row lock, not already committed")
                        .isFalse();

                create.set(executor.submit(() -> runInTransaction(createPid, createFailure, createFinished,
                        () -> rivalHoldId.set(
                                holdService.createHold(rivalId, new HoldRequest(List.of(contestedSeatId))).id()))));
                // Pre-fix this returns once the creating thread is parked on
                // the holds row the release is sitting on - the second half of
                // the cycle. Post-fix the release holds no holds row, so the
                // create just runs to completion; both outcomes are fine, and
                // the point is that the test never commits below until the
                // creating thread has definitively taken the contested seat.
                awaitBlockedOnRowLockIn(createPid, "holds", "the creating thread", createFinished);
            });

            release.get().get(30, TimeUnit.SECONDS);
            create.get().get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertNoLockFailure("the release", releaseFailure.get());
        assertNoLockFailure("the create", createFailure.get());

        // The creating user asked for a seat whose hold had genuinely expired,
        // so it must have got it. (Pre-fix this is the request Postgres was
        // most likely to pick as the deadlock victim, since it waited longest.)
        assertThat(rivalHoldId.get()).isNotZero();
        EventSeat contested = eventSeatRepository.findById(contestedSeatId).orElseThrow();
        assertThat(contested.getStatus()).isEqualTo(EventSeatStatus.HELD);
        assertThat(contested.getCurrentHold()).isNotNull();
        assertThat(contested.getCurrentHold().getId())
                .as("the contested seat belongs to the new hold")
                .isEqualTo(rivalHoldId.get());
        assertThat(holdRepository.findById(rivalHoldId.get()).orElseThrow().getStatus())
                .isEqualTo(HoldStatus.ACTIVE);

        // Whichever way the two transactions ordered themselves, the released
        // hold ends up not-ACTIVE: either the release expired it (ADR-0007) or
        // the create's lazy-expiry reconciliation did (ADR-0002).
        assertThat(holdRepository.findById(ownerHoldId).orElseThrow().getStatus())
                .isNotEqualTo(HoldStatus.ACTIVE);

        // The parked seat is the release's own, so it is either given back or
        // left where it was - but it never ends up on the new hold.
        EventSeat parked = eventSeatRepository.findById(parkedSeatId).orElseThrow();
        if (parked.getCurrentHold() != null) {
            assertThat(parked.getCurrentHold().getId()).isEqualTo(ownerHoldId);
        }

        // The release either succeeded or was refused with a deliberate 409:
        // the create reconciled the expired hold first, so by the time the
        // release read the holds row under its own lock, the hold was already
        // EXPIRED. That is the correct answer to "release a hold that has
        // already expired" (ADR-0007), not an error path - and per ADR-0009's
        // HOLD_EXPIRED/HOLD_NOT_ACTIVE split, a stored-EXPIRED hold reports
        // HOLD_EXPIRED, not HOLD_NOT_ACTIVE (which narrows to CONVERTED holds).
        if (releaseFailure.get() != null) {
            ApiException failure = (ApiException) releaseFailure.get();
            assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(failure.getCode()).isEqualTo("HOLD_EXPIRED");
        }
    }

    /**
     * Fails with the whole cause chain rather than a bare type mismatch: the
     * failure this test exists to catch is a {@code CannotAcquireLockException}
     * buried several wrappers deep, and the useful part of the diagnosis is
     * the Postgres message ("deadlock detected ... Process A waits for
     * ShareLock on transaction N; blocked by process B").
     */
    private void assertNoLockFailure(String who, Throwable failure) {
        if (failure == null || failure instanceof ApiException) {
            // ApiException is the handled path: GlobalExceptionHandler turns
            // it into the documented 4xx, never a 500.
            return;
        }
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null && chain.length() < 4000; t = t.getCause()) {
            chain.append("\n  caused by: ").append(t.getClass().getName()).append(": ").append(t.getMessage());
            if (t.getCause() == t) {
                break;
            }
        }
        throw new AssertionError(who + " failed with something other than a deliberate ApiException, so "
                + "GlobalExceptionHandler's catch-all turns it into a 500 on a legitimate request:" + chain, failure);
    }

    private void runInTransaction(AtomicLong pidSink, AtomicReference<Throwable> failureSink,
            AtomicBoolean finishedFlag, Runnable body) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // Published before any locking so the coordinating thread can
                // scope its pg_locks poll to this exact backend. JdbcTemplate
                // runs on the transaction-bound connection, so this is the
                // same backend the JPA statements below use (verified: the
                // JPA session and JdbcTemplate report the same
                // pg_backend_pid inside one TransactionTemplate).
                pidSink.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Long.class));
                body.run();
            });
        } catch (Throwable t) {
            failureSink.set(t);
        } finally {
            finishedFlag.set(true);
        }
    }

    private void expireOwnerHoldInItsOwnTransaction() {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(status ->
                holdRepository.findById(ownerHoldId).orElseThrow().setExpiresAt(Instant.now().minusSeconds(120)));
    }

    /**
     * Waits until the given backend is genuinely parked on a row lock in the
     * given table, and returns true; returns false if {@code finishedEarly}
     * flips first (only passed where finishing early is a legitimate
     * outcome). Fails loudly on timeout rather than proceeding: a thread that
     * never parked would sail through and turn this into a test that passes
     * for the wrong reason.
     *
     * <p>The predicate is scoped two ways, both of which matter. <b>By PID</b>,
     * because this runs in a shared Spring context in which {@code
     * HoldSweepService}'s 30-second job is live and hunting for exactly the
     * expired-hold rows this test sets up - an unscoped "is anyone blocked?"
     * poll can be satisfied by the sweep and release the coordinating thread
     * early. <b>By relation</b>, so a thread parked on the wrong table can't
     * be mistaken for one parked on the right one.
     *
     * <p>The join looks indirect because of how Postgres represents a row-lock
     * wait: the ungranted entry is a {@code ShareLock} on the holder's {@code
     * transactionid} and carries no relation at all. The waiter's link to the
     * table is the {@code tuple} lock it holds while waiting, so that is what
     * the relation test has to key off. (Observed directly against Postgres
     * before it was written, rather than guessed.)
     */
    private boolean awaitBlockedOnRowLockIn(AtomicLong pid, String relation, String who, AtomicBoolean finishedEarly) {
        Instant deadline = Instant.now().plus(BLOCK_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (finishedEarly != null && finishedEarly.get()) {
                return false;
            }
            long backend = pid.get();
            if (backend != 0) {
                Integer waiting = jdbcTemplate.queryForObject("""
                        select count(*)
                        from pg_locks waiting
                        join pg_locks tuple_lock
                          on tuple_lock.pid = waiting.pid
                         and tuple_lock.locktype = 'tuple'
                         and tuple_lock.relation = ?::regclass
                        where waiting.pid = ?
                          and not waiting.granted
                        """, Integer.class, relation, backend);
                if (waiting != null && waiting > 0) {
                    return true;
                }
            }
            sleep();
        }
        throw new AssertionError(who + " (backend pid " + pid.get() + ") was not parked on a " + relation
                + " row lock within " + BLOCK_TIMEOUT + ", so this run proves nothing. Locks held by that backend: "
                + locksHeldBy(pid.get()));
    }

    private List<Map<String, Object>> locksHeldBy(long backend) {
        return jdbcTemplate.queryForList("""
                select l.locktype, coalesce(c.relname, '-') as relation, l.mode, l.granted
                from pg_locks l
                left join pg_class c on c.oid = l.relation
                where l.pid = ?
                """, backend);
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
        return eventSeatRepository.save(EventSeat.builder()
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
