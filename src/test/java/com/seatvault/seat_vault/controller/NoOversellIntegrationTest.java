package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.dto.CreateBookingRequest;
import com.seatvault.seat_vault.dto.HoldRequest;
import com.seatvault.seat_vault.entity.Booking;
import com.seatvault.seat_vault.entity.BookingSeat;
import com.seatvault.seat_vault.entity.BookingStatus;
import com.seatvault.seat_vault.entity.Event;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import com.seatvault.seat_vault.entity.Payment;
import com.seatvault.seat_vault.entity.Seat;
import com.seatvault.seat_vault.entity.Venue;
import com.seatvault.seat_vault.repository.BookingRepository;
import com.seatvault.seat_vault.repository.BookingSeatRepository;
import com.seatvault.seat_vault.repository.EventRepository;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.PaymentRepository;
import com.seatvault.seat_vault.repository.SeatRepository;
import com.seatvault.seat_vault.repository.UserRepository;
import com.seatvault.seat_vault.repository.VenueRepository;
import com.seatvault.seat_vault.security.JwtService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * No-oversell coverage over a small private pool of {@link EventSeat}s: many
 * threads race through the full {@code POST /api/holds} &rarr;
 * {@code POST /api/bookings} &rarr; {@code POST /api/bookings/{id}/confirm}
 * pipeline concurrently, and no seat may ever be won twice.
 *
 * <p><b>Known and accepted limitation:</b> contention here only ever bites at
 * the hold stage. A {@code Hold} is single-owner by construction (only its
 * creating user can convert or release it), so once a thread wins its hold it
 * books and confirms completely unopposed - there is no further contention to
 * observe at those later stages in this class. Racing many concurrent callers
 * against the booking/confirm stages of a *shared* resource (e.g. the same
 * booking or hold) is T-002's job, not this class's.
 *
 * <p>Two race shapes, two invariants (deliberately different - see each
 * test's own comment):
 * <ul>
 *   <li>Shape (a): round-robin, one seat per thread, every seat guaranteed at
 *       least one attempt - {@code booked_count == seat_count} holds exactly.
 *   <li>Shape (b): overlapping multi-seat holds - hold creation is
 *       all-or-nothing, so a seat can legitimately end {@code AVAILABLE}
 *       because everyone who wanted it also wanted something they couldn't
 *       get. Asserting exact seat-count equality here would be flaky by
 *       design, not just by accident.
 * </ul>
 *
 * <p>Neither test method is {@code @Transactional}: every racing thread needs
 * its own real transaction/connection for {@code SELECT ... FOR UPDATE}
 * (ADR-0001) to mean anything, so every row here really commits. Each test
 * gets a fresh private Venue/Event/EventSeat pool built in {@link #setUp()}
 * (mirroring {@code BookingIntegrationTest#setUp}), including the far-future
 * {@code startsAt}, so those real commits can never perturb {@code
 * EventIntegrationTest}'s startsAt-ordering assertions against the seeded
 * events.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class NoOversellIntegrationTest {

    private static final String SEEDED_EMAIL = "alice@example.com";
    // Shape (a) needs 5 seats; shape (b) needs 8 (see each test's own
    // Javadoc for why). Each @BeforeEach builds a fresh pool sized for the
    // larger of the two and every test just uses the slice it needs, so
    // there is one seat-creation path instead of two.
    private static final int SHAPE_A_SEAT_COUNT = 5;
    private static final int SHAPE_B_SEAT_COUNT = 8;
    private static final int SEAT_POOL_SIZE = Math.max(SHAPE_A_SEAT_COUNT, SHAPE_B_SEAT_COUNT);
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
    private BookingRepository bookingRepository;

    @Autowired
    private BookingSeatRepository bookingSeatRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private List<EventSeat> eventSeats;

    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("No-Oversell Test Venue " + System.nanoTime())
                .address("1 Test Way")
                .build());
        Event event = eventRepository.save(Event.builder()
                .venue(venue)
                .name("No-Oversell Test Event")
                // Deliberately well after both seeded events' startsAt
                // (2026-09-15/2026-09-22): neither test method below is
                // @Transactional (each racing thread needs its own real
                // transaction/connection for the row locks under test to mean
                // anything), so this Event really commits, and
                // EventIntegrationTest asserts the seeded events are
                // first/second when ordered by startsAt - an earlier date
                // here would silently break that ordering assertion for
                // every other test class in the suite.
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
     * 5 seats, 20 threads; thread {@code i} targets seat {@code i % 5}. Every
     * seat gets exactly 4 attempts, so exactly one thread per seat wins its
     * hold (5 winners) and the other 15 lose. Since holds are single-owner
     * (see class Javadoc), every winner then books and confirms unopposed, so
     * this is also a deterministic, exact assertion: {@code booked_count ==
     * seat_count}, not a bound.
     */
    @Test
    void roundRobinFiveSeatsTwentyThreadsExactlyFiveWinAndBook() throws Exception {
        String token = tokenFor();
        int threadCount = 20;
        List<EventSeat> shapeASeats = eventSeats.subList(0, SHAPE_A_SEAT_COUNT);

        List<PipelineResult> results = race(threadCount, i -> {
            long seatId = shapeASeats.get(i % SHAPE_A_SEAT_COUNT).getId();
            return () -> attemptPipeline(token, List.of(seatId));
        });

        assertNo500s(results);

        List<PipelineResult> winners = results.stream().filter(PipelineResult::wonHold).toList();
        List<PipelineResult> losers = results.stream().filter(r -> !r.wonHold()).toList();
        assertThat(winners).hasSize(SHAPE_A_SEAT_COUNT);
        assertThat(losers).hasSize(threadCount - SHAPE_A_SEAT_COUNT);
        assertLosersRejectedWithContentionCode(losers);
        // Known limitation (see class Javadoc): a Hold is single-owner, so
        // every thread that wins its hold necessarily books and confirms
        // unopposed - there is no further contention to lose at those stages
        // in this shape.
        assertWinnersBookedAndConfirmed(winners);

        List<EventSeat> reloadedSeats = reloadSeats(shapeASeats);
        long bookedCount =
                reloadedSeats.stream().filter(s -> s.getStatus() == EventSeatStatus.BOOKED).count();
        // Exact, deterministic invariant for this shape: every seat got an
        // attempt, so every seat ends up booked - not merely "at most".
        assertThat(bookedCount).isEqualTo(SHAPE_A_SEAT_COUNT);
        assertThat(reloadedSeats).allSatisfy(
                s -> assertThat(s.getStatus()).isEqualTo(EventSeatStatus.BOOKED));

        Set<Long> bookedSeatIds = assertNoSeatDoubleBookedAndPriceMatchesPayment(winners);
        assertThat(bookedSeatIds).hasSize(SHAPE_A_SEAT_COUNT);
    }

    /**
     * 8 seats, 12 threads, each requesting 3 consecutive seats (thread
     * {@code i} wants seats {@code {i, i+1, i+2} mod 8}). With 8 seats and
     * 3-seat requests, two <em>disjoint</em> requests can both succeed (e.g.
     * {@code {0,1,2}} and {@code {3,4,5}}), so multiple concurrent
     * multi-seat holds can legitimately commit at the same time - an
     * invariant shape (a) above cannot produce, since it is one seat per
     * thread. That is the actual reason this shape exists. (An earlier
     * version of this test used only 5 seats, where every pair of 3-seat
     * subsets necessarily intersects and at most one thread could ever win -
     * a strictly weaker, more expensive duplicate of shape (a).)
     *
     * <p>With 12 threads over 8 seats indexed mod 8, threads 8-11 request
     * the exact same three-seat sets as threads 0-3 respectively - so this
     * is 8 distinct overlapping requests, not 12 independent ones. That's
     * fine (it only adds more contenders on the seats already contended),
     * but worth knowing when reading the winner counts below.
     *
     * <p><b>This shape does NOT exercise {@code HoldService}'s ascending-id
     * Postgres lock ordering</b> ({@code HoldService.java:75}). {@code
     * createHold} acquires <em>all</em> of a request's Redis locks
     * (non-blocking {@code SETNX} via {@code RedisLockService.tryLock}, which
     * never waits) before doing any Postgres work, and holds them through the
     * {@code finally} block. So while Redis is up, two threads with
     * overlapping seat sets can never both be inside {@code
     * createHoldWithSeatsLocked} at once - the loser is rejected at the
     * Redis tier before it ever calls {@code findByIdForUpdate}, meaning the
     * Postgres ordering loop only ever runs for a thread whose seats nobody
     * else can be holding right then. There is nothing here for that loop to
     * order against. That path is reachable only with Redis <em>down</em>
     * (where {@code tryLock} fail-opens for everyone and every thread floods
     * Postgres at once) - that coverage lives in T-003, not here.
     *
     * <p>The invariant here is deliberately weaker than shape (a) above, and
     * that is correct, not a bug in this test: hold creation is all-or-
     * nothing, so a thread that wins one of its three seats but loses another
     * rolls back all three, and a seat can legitimately end up
     * {@code AVAILABLE} forever because every thread that wanted it also
     * wanted at least one seat it couldn't get. This class does not assert
     * {@code booked_count == seat_count} for this shape, and does not assert
     * a fixed winner count either - which requests happen to win the Redis
     * race is non-deterministic, so winners are derived from the actual
     * results. (Observed empirically across repeated local runs: 1 or 2
     * winning threads, which lines up with the seat math - 8 seats can fit
     * at most {@code floor(8/3) = 2} disjoint 3-seat requests, and at least 1
     * thread always wins since the first Redis {@code SETNX} on an empty key
     * always succeeds.) What is asserted: no seat is ever double-booked,
     * nothing deadlocks or 500s, and nothing is left stranded {@code HELD}.
     */
    @Test
    void overlappingMultiSeatHoldsTwelveThreadsNoOversell() throws Exception {
        String token = tokenFor();
        int threadCount = 12;

        List<PipelineResult> results = race(threadCount, i -> {
            List<Long> seatIds = List.of(
                    eventSeats.get(i % SHAPE_B_SEAT_COUNT).getId(),
                    eventSeats.get((i + 1) % SHAPE_B_SEAT_COUNT).getId(),
                    eventSeats.get((i + 2) % SHAPE_B_SEAT_COUNT).getId());
            return () -> attemptPipeline(token, seatIds);
        });

        assertNo500s(results);

        // Winner count is not fixed here (see class-level Javadoc on this
        // method): derive it from the actual results rather than asserting a
        // literal, so the test stays correct regardless of which disjoint
        // requests happen to win the Redis race.
        List<PipelineResult> winners = results.stream().filter(PipelineResult::wonHold).toList();
        List<PipelineResult> losers = results.stream().filter(r -> !r.wonHold()).toList();
        assertLosersRejectedWithContentionCode(losers);
        // Known limitation (see class Javadoc): a Hold is single-owner, so
        // every thread that wins its hold necessarily books and confirms
        // unopposed.
        assertWinnersBookedAndConfirmed(winners);

        List<EventSeat> reloadedSeats = reloadSeats(eventSeats);
        // Weaker invariant than shape (a), deliberately: every seat must end
        // either BOOKED or AVAILABLE - never stranded HELD with a dead
        // current_hold - but AVAILABLE is a legitimate outcome here.
        assertThat(reloadedSeats).allSatisfy(s -> assertThat(s.getStatus())
                .isIn(EventSeatStatus.BOOKED, EventSeatStatus.AVAILABLE));
        assertThat(reloadedSeats).allSatisfy(s -> {
            if (s.getStatus() == EventSeatStatus.AVAILABLE) {
                assertThat(s.getCurrentHold()).isNull();
            }
        });

        Set<Long> bookedSeatIds = assertNoSeatDoubleBookedAndPriceMatchesPayment(winners);
        long bookedCount =
                reloadedSeats.stream().filter(s -> s.getStatus() == EventSeatStatus.BOOKED).count();
        // Every BOOKED seat belongs to exactly one winning booking, and vice
        // versa - the actual "no oversell" invariant for this shape.
        assertThat(bookedCount).isEqualTo(bookedSeatIds.size());
    }

    /**
     * Runs {@code threadCount} callables built by {@code taskFactory} against
     * a shared start gate, mirroring {@code
     * HoldIntegrationTest#concurrentHoldRequestsForSameSeatOnlyOneWins}'s
     * latch/executor/futures-with-timeout/{@code finally}-shutdown structure.
     */
    private List<PipelineResult> race(int threadCount, IntCallableFactory taskFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<PipelineResult>> tasks = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            Callable<PipelineResult> inner = taskFactory.create(i);
            tasks.add(() -> {
                startGate.await();
                return inner.call();
            });
        }

        try {
            List<Future<PipelineResult>> futures = tasks.stream().map(executor::submit).toList();
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

    /**
     * Drives one thread's full hold &rarr; booking &rarr; confirm attempt,
     * stopping at the first non-2xx step. Runs entirely on the calling
     * (racing) thread's own transaction/connection, matching every other
     * concurrency test in this suite.
     */
    private PipelineResult attemptPipeline(String token, List<Long> seatIds) throws Exception {
        MockHttpServletResponse holdResponse = mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldRequest(seatIds))))
                .andReturn()
                .getResponse();
        int holdStatus = holdResponse.getStatus();
        if (holdStatus != 201) {
            String code = errorCode(holdResponse);
            return new PipelineResult(holdStatus, code, null, -1, null, -1);
        }
        long holdId = objectMapper.readTree(holdResponse.getContentAsString()).get("id").asLong();

        MockHttpServletResponse bookingResponse = mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBookingRequest(holdId))))
                .andReturn()
                .getResponse();
        int bookingStatus = bookingResponse.getStatus();
        if (bookingStatus != 201) {
            return new PipelineResult(holdStatus, null, holdId, bookingStatus, null, -1);
        }
        long bookingId = objectMapper.readTree(bookingResponse.getContentAsString()).get("id").asLong();

        MockHttpServletResponse confirmResponse = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/bookings/{id}/confirm", bookingId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn()
                .getResponse();
        int confirmStatus = confirmResponse.getStatus();

        return new PipelineResult(holdStatus, null, holdId, bookingStatus, bookingId, confirmStatus);
    }

    private String errorCode(MockHttpServletResponse response) throws Exception {
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        JsonNode code = json.get("code");
        return code == null ? null : code.asText();
    }

    private void assertNo500s(List<PipelineResult> results) {
        assertThat(results).extracting(PipelineResult::holdStatus).noneMatch(status -> status == 500);
        assertThat(results).extracting(PipelineResult::bookingStatus).noneMatch(status -> status == 500);
        assertThat(results).extracting(PipelineResult::confirmStatus).noneMatch(status -> status == 500);
    }

    private void assertLosersRejectedWithContentionCode(List<PipelineResult> losers) {
        assertThat(losers).allSatisfy(r -> {
            assertThat(r.holdStatus()).isEqualTo(409);
            // Per ADR-0009, these are the only two contention codes reachable
            // at the hold stage.
            assertThat(r.holdErrorCode()).isIn("SEAT_ALREADY_HELD", "SEAT_ALREADY_BOOKED");
        });
    }

    private void assertWinnersBookedAndConfirmed(List<PipelineResult> winners) {
        assertThat(winners).allSatisfy(r -> {
            assertThat(r.bookingStatus()).isEqualTo(201);
            assertThat(r.confirmStatus()).isEqualTo(200);
        });
    }

    /**
     * Fresh transactional reads only, taken after every racing thread has
     * joined - never from entities captured mid-race (see {@code
     * HoldIntegrationTest:260-266} for why lazy proxies bite here). Each
     * repository call below is its own independent, fully-committed
     * transaction.
     */
    private List<EventSeat> reloadSeats(List<EventSeat> seats) {
        return seats.stream()
                .map(s -> eventSeatRepository.findById(s.getId()).orElseThrow())
                .toList();
    }

    /**
     * For every winning thread: confirms its Booking really reached
     * CONFIRMED, that the seat it won is never reused by another winner (the
     * core no-oversell check), and - incidentally, per ADR-0003 - that the
     * CONFIRMED booking's payment amount equals the sum of its seats'
     * hold-time price snapshots.
     */
    private Set<Long> assertNoSeatDoubleBookedAndPriceMatchesPayment(List<PipelineResult> winners) {
        Set<Long> bookedSeatIds = new HashSet<>();
        for (PipelineResult winner : winners) {
            Booking booking = bookingRepository.findById(winner.bookingId()).orElseThrow();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

            List<BookingSeat> bookingSeats = bookingSeatRepository.findByBookingId(winner.bookingId());
            for (BookingSeat bookingSeat : bookingSeats) {
                // .getId() on a lazy proxy is safe (no DB hit); the id is all
                // this check needs.
                Long seatId = bookingSeat.getEventSeat().getId();
                assertThat(bookedSeatIds.add(seatId))
                        .as("event seat %s must not appear in more than one booking", seatId)
                        .isTrue();
            }

            BigDecimal priceSnapshotSum = bookingSeats.stream()
                    .map(BookingSeat::getPriceSnapshot)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Payment payment = paymentRepository.findByBookingId(winner.bookingId()).orElseThrow();
            assertThat(payment.getAmount()).isEqualByComparingTo(priceSnapshotSum);
        }
        return bookedSeatIds;
    }

    private String tokenFor() {
        long userId = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow().getId();
        return jwtService.generateToken(userId, SEEDED_EMAIL);
    }

    @FunctionalInterface
    private interface IntCallableFactory {
        Callable<PipelineResult> create(int i);
    }

    private record PipelineResult(
            int holdStatus, String holdErrorCode, Long holdId, int bookingStatus, Long bookingId, int confirmStatus) {

        boolean wonHold() {
            return holdStatus == 201;
        }
    }
}
