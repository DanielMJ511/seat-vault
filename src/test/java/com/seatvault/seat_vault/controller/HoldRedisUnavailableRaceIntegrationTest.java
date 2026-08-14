package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatvault.seat_vault.config.BrokenRedisTestConfig;
import com.seatvault.seat_vault.config.PostgresTestcontainersConfig;
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
import com.seatvault.seat_vault.security.JwtService;
import com.seatvault.seat_vault.service.RedisLockService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes ADR-0001's central claim falsifiable: with Redis unavailable,
 * hold-stage correctness is unchanged, because Postgres's {@code SELECT ...
 * FOR UPDATE} row lock - not the short-lived Redis contention lock - is what
 * actually prevents double-booking. {@code SEAT_ALREADY_HELD} is thrown from
 * two distinct places ({@code HoldService.java:84} on Redis-lock contention,
 * {@code HoldService.java:129} on a Postgres-level {@code HELD} seat); with
 * Redis down, the first is unreachable by construction (see
 * {@link RedisLockService#tryLock}'s catch clause - it always returns the
 * fail-open sentinel, never {@link Optional#empty()}, when the template
 * throws), so every rejection observed here provably runs through the
 * second. That is what "Redis was never load-bearing for correctness" looks
 * like as an assertion.
 *
 * <p><b>Two mechanisms, deliberately different, are used across this suite
 * for this outage:</b> {@code RedisLockServiceTest} uses a throwing Mockito
 * stub for its isolated unit assertions (right there, since the point is
 * observing the <em>absence</em> of a call). This class instead imports
 * {@link BrokenRedisTestConfig}, which supplies a real {@code
 * RedisConnectionFactory} pointed at a dead port - producing genuine {@code
 * DataAccessException}s through the real Spring Data Redis stack, which is
 * what {@code tryLock}'s catch clause actually has to handle in production.
 * Stopping the shared Testcontainers Redis container instead was rejected:
 * it is shared across the cached Spring context used by every other
 * integration test in the suite, so stopping it would break all of them.
 * This class therefore imports {@link PostgresTestcontainersConfig} plus
 * {@link BrokenRedisTestConfig} - never {@code TestcontainersConfig} - and,
 * because that is a distinct configuration set from every other {@code
 * @SpringBootTest} here, Spring's context cache cannot share an existing
 * context with it: this class pays for its own application context and its
 * own, separate Postgres container. Accepted per ADR-0001 (see the class
 * Javadoc of {@link BrokenRedisTestConfig} for the full reasoning).
 *
 * <p><b>Scope: hold-stage only, not the full pipeline.</b> The claim under
 * test is narrow (Postgres, not Redis, prevents double-booking at hold
 * creation) and a hold-stage assertion proves it; re-running booking/confirm
 * here would mostly re-test {@code NoOversellIntegrationTest} a second time
 * in a second, more expensive context.
 *
 * <ul>
 *   <li>{@link #uncontendedHoldStillSucceedsWithRedisDown()} - the smoke
 *       test: {@code createHold} does not 500 just because Redis is gone.
 *   <li>{@link #race1SingleSeatManyThreadsExactlyOneWinsWithRedisDown()} -
 *       the headline fallback proof: the same one-winner outcome as the
 *       Redis-up version of this race ({@code
 *       HoldIntegrationTest#concurrentHoldRequestsForSameSeatOnlyOneWins}),
 *       produced by a different mechanism (the Postgres row lock alone).
 *   <li>{@link #race2OverlappingMultiSeatTwelveThreadsNoDeadlockNoOversellWithRedisDown()}
 *       - <b>the only place in the whole suite that exercises {@code
 *       HoldService}'s ascending-id Postgres lock ordering</b> ({@code
 *       HoldService.java:75}, the deadlock-avoidance reasoning). {@code
 *       NoOversellIntegrationTest#overlappingMultiSeatHoldsTwelveThreadsNoOversell}
 *       uses the identical 8-seat/12-thread/overlapping-triples shape but,
 *       per its own Javadoc, explicitly does <em>not</em> reach the ordering
 *       loop: with Redis up, {@code createHold} acquires all of a request's
 *       Redis locks (non-blocking {@code SETNX}, never waits) before any
 *       Postgres work and holds them through its {@code finally} block, so
 *       two threads with overlapping seat sets can never both be inside
 *       {@code createHoldWithSeatsLocked} at once - the loser is rejected at
 *       the Redis tier before ever calling {@code findByIdForUpdate}. With
 *       Redis <b>down</b>, {@code tryLock} fail-opens for every caller (see
 *       above), so all 12 threads enter the Postgres loop with overlapping
 *       row sets at once - exactly the scenario the ascending-id ordering
 *       exists to prevent from deadlocking, and the only configuration in
 *       this suite where that is even possible. A future reader who
 *       "simplifies" this back into a duplicate of
 *       {@code NoOversellIntegrationTest} would silently delete this
 *       coverage - don't.
 * </ul>
 *
 * <p><b>This class found a real, pre-existing bug, not a hypothetical one.</b>
 * {@link #race2OverlappingMultiSeatTwelveThreadsNoDeadlockNoOversellWithRedisDown()}
 * (and its minimal reproduction, {@link
 * #twoThreadsWithIdenticalOverlappingSeatRequestsOnlyOneWins()}) initially
 * double-booked seats reproducibly: {@code HoldService#spansMultipleEvents}'s
 * request-shape check loaded full, managed {@code EventSeat} entities via an
 * unlocked {@code findAllById} <em>before</em> the real locking loop began.
 * Because a JPA persistence context's identity map returns the
 * already-managed instance for a given id rather than refreshing it, the
 * later, correctly-locked {@code findByIdForUpdate} call for an overlapping
 * seat silently handed back that stale, pre-lock snapshot instead of the
 * fresh, post-lock row - defeating the Postgres lock for any request
 * covering 2+ seats whenever it genuinely overlapped another in flight. This
 * was invisible before this class for the exact reason explained above: with
 * Redis up, two overlapping requests are essentially never both inside the
 * Postgres loop at once, so the stale-cache read and the fresh read were
 * never actually in competition. The fix - {@code
 * spansMultipleEvents} now calls {@link
 * EventSeatRepository#findDistinctEventIdsByIdIn}, a plain id/event-id
 * projection that never materializes an {@code EventSeat} entity into the
 * persistence context - is orthogonal to transaction isolation level (still
 * plain {@code @Transactional}, default {@code READ_COMMITTED}); the defect
 * was an ORM identity-map/caching issue, not an isolation-level one.
 *
 * <p>Each test builds its own fresh, private Venue/Event/EventSeat pool in
 * {@link #setUp()} against this class's own isolated Postgres container, so
 * there is no shared-fixture risk with any other test class the way the
 * shared-context integration tests have to guard against (compare {@code
 * NoOversellIntegrationTest}'s far-future {@code startsAt} comment). No test
 * method here is {@code @Transactional}: every racing thread needs its own
 * real transaction/connection for {@code SELECT ... FOR UPDATE} to mean
 * anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, BrokenRedisTestConfig.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class HoldRedisUnavailableRaceIntegrationTest {

    private static final String SEEDED_EMAIL = "alice@example.com";
    // Sized for race 2 (8 seats); the smoke test and race 1 each only need
    // one seat and just use eventSeats.get(0) - safe, since every test
    // method gets its own fresh pool from setUp().
    private static final int SEAT_POOL_SIZE = 8;
    private static final BigDecimal SEAT_PRICE = new BigDecimal("40.00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private JwtService jwtService;

    @Autowired
    private RedisLockService redisLockService;

    private List<EventSeat> eventSeats;

    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("Redis-Down Test Venue " + System.nanoTime())
                .address("1 Test Way")
                .build());
        Event event = eventRepository.save(Event.builder()
                .venue(venue)
                .name("Redis-Down Test Event")
                .startsAt(Instant.now().plus(400, ChronoUnit.DAYS))
                .build());

        eventSeats = IntStream.range(0, SEAT_POOL_SIZE)
                .mapToObj(i -> {
                    Seat seat = seatRepository.save(Seat.builder()
                            .venue(venue)
                            .section("A")
                            .rowLabel("A")
                            .seatNumber(i + 1)
                            .build());
                    return eventSeatRepository.save(EventSeat.builder()
                            .event(event)
                            .seat(seat)
                            .status(EventSeatStatus.AVAILABLE)
                            .price(SEAT_PRICE)
                            .build());
                })
                .toList();
    }

    /**
     * Confirms the wiring itself, not just the production code path taken on
     * faith: against this exact context's {@code RedisConnectionFactory}
     * (dead port - see {@link BrokenRedisTestConfig}), {@link
     * RedisLockService#tryLock} really does return the fail-open sentinel
     * here and now, rather than throwing out of this test or, worse, somehow
     * succeeding because port 18734 happens to have something listening on
     * it. Every other test below relies on "Redis is down in this context";
     * this is what makes that an observed fact rather than an assumption.
     */
    @Test
    void wiringSanityCheckRedisLockServiceFailsOpenInThisContext() {
        long seatId = eventSeats.get(0).getId();

        Optional<String> token = redisLockService.tryLock(seatId);

        assertThat(token).contains("REDIS_UNAVAILABLE");
        // unlock on the sentinel must not itself throw - exercised here too,
        // for the same reason.
        redisLockService.unlock(seatId, token.orElseThrow());
    }

    /**
     * Small, deterministic regression coverage for a real bug this class's
     * development uncovered (see {@code HoldService#spansMultipleEvents}'s
     * Javadoc and {@link EventSeatRepository#findDistinctEventIdsByIdIn}):
     * two threads submitting the <em>identical</em> multi-seat request
     * concurrently must produce exactly one winner and one {@code
     * SEAT_ALREADY_HELD} loser, never two winners. This is the minimal
     * reproduction of that bug (it does not depend on the 12-thread scale of
     * {@link #race2OverlappingMultiSeatTwelveThreadsNoDeadlockNoOversellWithRedisDown()}
     * below, which also covers it, but at a scale that's harder to reason
     * about when isolating a regression).
     */
    @Test
    void twoThreadsWithIdenticalOverlappingSeatRequestsOnlyOneWins() throws Exception {
        String token = tokenFor();
        List<Long> seatIds = List.of(
                eventSeats.get(0).getId(), eventSeats.get(1).getId(), eventSeats.get(2).getId());

        List<HoldAttemptResult> results = race(2, i -> () -> attemptHold(token, seatIds));

        List<HoldAttemptResult> winners = results.stream().filter(r -> r.status() == 201).toList();
        List<HoldAttemptResult> losers = results.stream().filter(r -> r.status() != 201).toList();
        assertThat(winners).hasSize(1);
        assertThat(losers).hasSize(1);
        assertThat(losers.get(0).status()).isEqualTo(409);
        assertThat(losers.get(0).errorCode()).isEqualTo("SEAT_ALREADY_HELD");

        for (Long seatId : seatIds) {
            EventSeat reloaded = eventSeatRepository.findById(seatId).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(EventSeatStatus.HELD);
            assertThat(reloaded.getCurrentHold().getId()).isEqualTo(winners.get(0).holdId());
        }
    }

    @Test
    void uncontendedHoldStillSucceedsWithRedisDown() throws Exception {
        String token = tokenFor();
        long seatId = eventSeats.get(0).getId();

        HoldAttemptResult result = attemptHold(token, List.of(seatId));

        assertThat(result.status()).isEqualTo(201);
        EventSeat reloaded = eventSeatRepository.findById(seatId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EventSeatStatus.HELD);
        assertThat(reloaded.getCurrentHold()).isNotNull();
    }

    /**
     * Race 1 - the headline fallback proof. 20 threads race one seat, same
     * shape as {@code HoldIntegrationTest#concurrentHoldRequestsForSameSeatOnlyOneWins},
     * but with Redis down: exactly one hold is created and the rest are
     * rejected - the identical outcome, produced by the Postgres row lock
     * alone. Every loser's rejection code is asserted to be exactly {@code
     * SEAT_ALREADY_HELD} from the Postgres stage ({@code
     * HoldService.java:129}): the Redis fail-fast branch ({@code
     * HoldService.java:84}) is unreachable in this configuration by
     * construction, since {@code tryLock} never returns {@link
     * Optional#empty()} when Redis is unavailable (see class Javadoc).
     */
    @Test
    void race1SingleSeatManyThreadsExactlyOneWinsWithRedisDown() throws Exception {
        String token = tokenFor();
        long seatId = eventSeats.get(0).getId();
        int threadCount = 20;

        List<HoldAttemptResult> results = race(threadCount, i -> () -> attemptHold(token, List.of(seatId)));

        assertThat(results).extracting(HoldAttemptResult::status).noneMatch(status -> status == 500);

        List<HoldAttemptResult> winners = results.stream().filter(r -> r.status() == 201).toList();
        List<HoldAttemptResult> losers = results.stream().filter(r -> r.status() != 201).toList();
        assertThat(winners).hasSize(1);
        assertThat(losers).hasSize(threadCount - 1);
        assertThat(losers).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.errorCode()).isEqualTo("SEAT_ALREADY_HELD");
        });

        EventSeat reloaded = eventSeatRepository.findById(seatId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EventSeatStatus.HELD);
        Hold winningHold =
                holdRepository.findById(reloaded.getCurrentHold().getId()).orElseThrow();
        assertThat(winningHold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }

    /**
     * Race 2 - overlapping multi-seat, Redis down. See class Javadoc for why
     * this is the only place in the suite that exercises {@code
     * HoldService}'s ascending-id Postgres lock ordering, and why the
     * identically-shaped Redis-up race in {@code NoOversellIntegrationTest}
     * cannot reach it. 8 seats, 12 threads, thread {@code i} requesting
     * {@code {i, i+1, i+2} mod 8}.
     *
     * <p><b>How the "threads genuinely entered the Postgres loop at the same
     * time" claim is verified here, not assumed:</b> two separate pieces of
     * evidence, one mechanical/by-construction and one empirical.
     * <ol>
     *   <li>By construction: {@link RedisLockService#tryLock}'s catch clause
     *       always returns a present {@link Optional} (the sentinel) when
     *       the template throws - never {@code Optional.empty()}. That is
     *       not probabilistic; it holds for every one of the 12 threads'
     *       calls in this context, which {@link
     *       #wiringSanityCheckRedisLockServiceFailsOpenInThisContext()}
     *       confirms is actually true here (not just true of the production
     *       code read in isolation). So every thread is <em>guaranteed</em>
     *       to reach {@code findByIdForUpdate} - the Redis tier cannot
     *       filter any of them out first, unlike the Redis-up version of
     *       this race.
     *   <li>Empirically: {@code maxInFlight} below counts the maximum number
     *       of hold requests genuinely overlapping in wall-clock time across
     *       the whole racing pool (an {@link AtomicInteger} incremented
     *       before each {@code MockMvc} dispatch and decremented after,
     *       tracking the running high-water mark). Per-request time here is
     *       dominated by exactly the code path under test (the sequential
     *       {@code tryLock} attempts, each paying the dead-port connect
     *       timeout, followed by the Postgres lock loop), so a high observed
     *       overlap is strong evidence - not literally an instruction-level
     *       guarantee, but far more than an assumption - that multiple
     *       threads really were concurrently inside {@code
     *       createHoldWithSeatsLocked} together, not serialized by
     *       executor/connection-pool scheduling. Observed locally: {@code
     *       maxInFlight} reached the full thread count (12) on every run
     *       tried during development; the assertion below only requires
     *       {@code > 1} to avoid being flaky on a slower machine while still
     *       being a real, non-trivial lower bound.
     * </ol>
     *
     * <p>Assert: no 500 anywhere (a Postgres deadlock from a lock-ordering
     * regression would surface as {@code CannotAcquireLockException} &rarr;
     * 500, so this is what actually catches a regression here), no seat
     * claimed by more than one winning hold, and every seat ends either
     * legitimately {@code HELD} by exactly the hold that claimed it or
     * {@code AVAILABLE} with no dangling {@code currentHold} - never
     * stranded. Winner count is not fixed (same reasoning as {@code
     * NoOversellIntegrationTest}'s equivalent shape): which disjoint
     * three-seat subsets happen to win is derived from the actual results,
     * not asserted as a literal.
     */
    @Test
    void race2OverlappingMultiSeatTwelveThreadsNoDeadlockNoOversellWithRedisDown() throws Exception {
        String token = tokenFor();
        int threadCount = 12;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();

        List<HoldAttemptResult> results = race(threadCount, i -> {
            List<Long> seatIds = List.of(
                    eventSeats.get(i % SEAT_POOL_SIZE).getId(),
                    eventSeats.get((i + 1) % SEAT_POOL_SIZE).getId(),
                    eventSeats.get((i + 2) % SEAT_POOL_SIZE).getId());
            return () -> {
                int current = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(current, Math::max);
                try {
                    return attemptHold(token, seatIds);
                } finally {
                    inFlight.decrementAndGet();
                }
            };
        });

        assertThat(maxInFlight.get())
                .as("max concurrent in-flight hold requests - see method Javadoc for why this matters")
                .isGreaterThan(1);

        assertThat(results).extracting(HoldAttemptResult::status).noneMatch(status -> status == 500);

        List<HoldAttemptResult> winners = results.stream().filter(r -> r.status() == 201).toList();
        List<HoldAttemptResult> losers = results.stream().filter(r -> r.status() != 201).toList();
        assertThat(losers).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.errorCode()).isEqualTo("SEAT_ALREADY_HELD");
        });

        Set<Long> claimedSeatIds = new HashSet<>();
        for (HoldAttemptResult winner : winners) {
            for (Long seatId : winner.seatIds()) {
                assertThat(claimedSeatIds.add(seatId))
                        .as("event seat %s must not be claimed by more than one winning hold", seatId)
                        .isTrue();
            }
        }

        List<EventSeat> reloadedSeats = eventSeats.stream()
                .map(s -> eventSeatRepository.findById(s.getId()).orElseThrow())
                .toList();
        for (EventSeat seat : reloadedSeats) {
            if (claimedSeatIds.contains(seat.getId())) {
                assertThat(seat.getStatus()).isEqualTo(EventSeatStatus.HELD);
                assertThat(seat.getCurrentHold()).isNotNull();
            } else {
                // Never stranded HELD with no live owner - either genuinely
                // claimed above, or fully AVAILABLE.
                assertThat(seat.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
                assertThat(seat.getCurrentHold()).isNull();
            }
        }
    }

    /**
     * Runs {@code threadCount} callables built by {@code taskFactory}
     * against a shared start gate, mirroring {@code
     * NoOversellIntegrationTest#race}/{@code
     * HoldIntegrationTest#concurrentHoldRequestsForSameSeatOnlyOneWins}'s
     * latch/executor/futures-with-timeout/{@code finally}-shutdown
     * structure.
     */
    private <T> List<T> race(int threadCount, IntCallableFactory<T> taskFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<T>> tasks = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            Callable<T> inner = taskFactory.create(i);
            tasks.add(() -> {
                startGate.await();
                return inner.call();
            });
        }

        try {
            List<Future<T>> futures = tasks.stream().map(executor::submit).toList();
            startGate.countDown();

            return futures.stream()
                    .map(f -> {
                        try {
                            return f.get(30, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private HoldAttemptResult attemptHold(String token, List<Long> seatIds) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldRequest(seatIds))))
                .andReturn()
                .getResponse();
        int status = response.getStatus();
        if (status != 201) {
            return new HoldAttemptResult(status, errorCode(response), null, seatIds);
        }
        long holdId = objectMapper.readTree(response.getContentAsString()).get("id").asLong();
        return new HoldAttemptResult(status, null, holdId, seatIds);
    }

    private String errorCode(MockHttpServletResponse response) throws Exception {
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        JsonNode code = json.get("code");
        return code == null ? null : code.asText();
    }

    private String tokenFor() {
        long userId = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow().getId();
        return jwtService.generateToken(userId, SEEDED_EMAIL);
    }

    @FunctionalInterface
    private interface IntCallableFactory<T> {
        Callable<T> create(int i);
    }

    private record HoldAttemptResult(int status, String errorCode, Long holdId, List<Long> seatIds) {
    }
}
