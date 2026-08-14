package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.dto.CreateBookingRequest;
import com.seatvault.seat_vault.dto.HoldRequest;
import com.seatvault.seat_vault.entity.Booking;
import com.seatvault.seat_vault.entity.BookingStatus;
import com.seatvault.seat_vault.entity.Event;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import com.seatvault.seat_vault.entity.Hold;
import com.seatvault.seat_vault.entity.HoldStatus;
import com.seatvault.seat_vault.entity.Payment;
import com.seatvault.seat_vault.entity.PaymentStatus;
import com.seatvault.seat_vault.entity.Seat;
import com.seatvault.seat_vault.entity.Venue;
import com.seatvault.seat_vault.repository.BookingRepository;
import com.seatvault.seat_vault.repository.EventRepository;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.HoldRepository;
import com.seatvault.seat_vault.repository.PaymentRepository;
import com.seatvault.seat_vault.repository.SeatRepository;
import com.seatvault.seat_vault.repository.UserRepository;
import com.seatvault.seat_vault.repository.VenueRepository;
import com.seatvault.seat_vault.security.JwtService;
import com.seatvault.seat_vault.service.SimulatedPaymentServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the two later stages of the hold &rarr; booking &rarr; confirm
 * pipeline that {@link NoOversellIntegrationTest} cannot reach: that class's
 * own Javadoc explains that a {@code Hold} is single-owner by construction,
 * so once a thread wins its hold it books and confirms completely unopposed
 * - there is no further contention to observe at those later stages there.
 * This class puts many threads on a *shared* {@code Booking} instead, where
 * a double-charge or double-release would actually hide.
 *
 * <ul>
 *   <li>{@link #allDeclineUnderLoadReleasesEverySeatAndFailsEveryBooking()}
 *       covers the confirm-stage decline path: a declined payment sets the
 *       {@code Booking} {@code FAILED} and releases its seats back to
 *       {@code AVAILABLE} - the mirror of {@code
 *       NoOversellIntegrationTest#roundRobinFiveSeatsTwentyThreadsExactlyFiveWinAndBook}'s
 *       happy-path invariant, but for the decline path, under load.
 *   <li>{@link #sharedHoldRaceProducesOneBookingAndOneCharge()} covers
 *       contention on a single shared {@code Booking} row: many threads
 *       racing {@code POST /api/bookings} for the same {@code Hold}, then
 *       many threads racing {@code POST /api/bookings/{id}/confirm} for the
 *       one {@code Booking} that wins - proving {@link
 *       com.seatvault.seat_vault.service.BookingService#confirmPayment}'s
 *       Booking row lock plus its not-{@code PENDING} early return make
 *       repeat confirms idempotent under real concurrent load, not just in
 *       the single-seat version of this race already covered by {@code
 *       BookingIntegrationTest#parallelConfirmCallsOnlyChargeOnce}.
 * </ul>
 *
 * <p>{@link com.seatvault.seat_vault.service.SimulatedPaymentServiceImpl}'s
 * {@code forcedOutcome} and {@code invocationCount} are process-wide
 * singleton state (see its own Javadoc), so both are reset in {@link
 * #tearDown()} exactly as {@code BookingIntegrationTest#tearDown} does -
 * otherwise a leftover forced outcome or a stale invocation count from one
 * test (or, within this class, from Method 1 running before Method 2) would
 * silently corrupt the other test's assertions.
 *
 * <p>Neither test method is {@code @Transactional}: every racing thread
 * needs its own real transaction/connection for the row locks under test
 * (ADR-0001) to mean anything, mirroring {@code NoOversellIntegrationTest}
 * and {@code BookingIntegrationTest}'s own non-transactional concurrency
 * tests. Each test gets a fresh private Venue/Event/EventSeat pool built in
 * {@link #setUp()}, including the far-future {@code startsAt}, so those real
 * commits can never perturb {@code EventIntegrationTest}'s startsAt-ordering
 * assertions against the seeded events.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class BookingConfirmLoadIntegrationTest {

    private static final String SEEDED_EMAIL = "alice@example.com";
    private static final int SEAT_COUNT = 4;
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
    private BookingRepository bookingRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SimulatedPaymentServiceImpl simulatedPaymentService;

    private List<EventSeat> eventSeats;

    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("Booking Confirm Load Test Venue " + System.nanoTime())
                .address("1 Test Way")
                .build());
        Event event = eventRepository.save(Event.builder()
                .venue(venue)
                .name("Booking Confirm Load Test Event")
                // Deliberately well after both seeded events' startsAt
                // (2026-09-15/2026-09-22): neither test method below is
                // @Transactional, so this Event really commits, and
                // EventIntegrationTest asserts the seeded events are
                // first/second when ordered by startsAt - an earlier date
                // here would silently break that ordering assertion for
                // every other test class in the suite.
                .startsAt(Instant.now().plus(400, ChronoUnit.DAYS))
                .build());

        eventSeats = IntStream.range(0, SEAT_COUNT)
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

    @AfterEach
    void tearDown() {
        simulatedPaymentService.resetForcedOutcome();
        simulatedPaymentService.resetInvocationCount();
    }

    /**
     * Confirm-stage decline coverage: 4 seats, 8 threads, thread {@code i}
     * targets seat {@code i % 4} - the same round-robin shape as {@code
     * NoOversellIntegrationTest}'s shape (a), so exactly one thread per seat
     * wins its hold (4 winners) and the other 4 lose there. Every winner then
     * books unopposed (holds are single-owner) and confirms against a
     * payment service forced to decline every charge. A declined payment
     * sets the {@code Booking} {@code FAILED} and calls {@code
     * releaseBookingSeats}, flipping its seat back {@code BOOKED} &rarr;
     * {@code AVAILABLE} - the transition this test exists to exercise under
     * concurrent load, mirroring {@code
     * NoOversellIntegrationTest#roundRobinFiveSeatsTwentyThreadsExactlyFiveWinAndBook}'s
     * happy-path invariant for the decline path instead.
     *
     * <p><b>Known limitation, stated plainly:</b> because holds are
     * single-owner, no two threads ever contend for the <em>same</em>
     * booking's confirm/release here - each of the 4 winning threads
     * declines and releases its own, independent seat. What this test does
     * prove is that four such releases, running concurrently against four
     * different seats, don't interfere with each other or with the four
     * losing hold attempts running at the same time (no 500s, no stranded
     * {@code BOOKED}/{@code HELD} seat). Racing multiple confirms against
     * one *shared* booking is Method 2's job, not this one's.
     */
    @Test
    void allDeclineUnderLoadReleasesEverySeatAndFailsEveryBooking() throws Exception {
        simulatedPaymentService.forceNextOutcome(PaymentStatus.FAILED);
        String token = tokenFor();
        int threadCount = 8;

        List<PipelineResult> results = race(threadCount, i -> {
            long seatId = eventSeats.get(i % SEAT_COUNT).getId();
            return () -> attemptPipeline(token, List.of(seatId));
        });

        assertNo500s(results);

        List<PipelineResult> winners = results.stream().filter(PipelineResult::wonHold).toList();
        List<PipelineResult> losers = results.stream().filter(r -> !r.wonHold()).toList();
        assertThat(winners).hasSize(SEAT_COUNT);
        assertThat(losers).hasSize(threadCount - SEAT_COUNT);
        assertThat(losers).allSatisfy(r -> {
            assertThat(r.holdStatus()).isEqualTo(409);
            assertThat(r.holdErrorCode()).isIn("SEAT_ALREADY_HELD", "SEAT_ALREADY_BOOKED");
        });

        // Every winner books unopposed and reaches confirm (200), but the
        // forced decline means the *outcome* is FAILED, not CONFIRMED - the
        // HTTP status is still 200 either way, since confirmPayment reports
        // a decline as a successful state transition, not an error.
        assertThat(winners).allSatisfy(r -> {
            assertThat(r.bookingStatus()).isEqualTo(201);
            assertThat(r.confirmStatus()).isEqualTo(200);
        });

        List<EventSeat> reloadedSeats = reloadSeats(eventSeats);
        assertThat(reloadedSeats).allSatisfy(s -> {
            assertThat(s.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
            assertThat(s.getCurrentHold()).isNull();
        });

        for (PipelineResult winner : winners) {
            Booking booking = bookingRepository.findById(winner.bookingId()).orElseThrow();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.FAILED);
            Payment payment = paymentRepository.findByBookingId(winner.bookingId()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        assertThat(simulatedPaymentService.invocationCount()).isEqualTo(winners.size());
    }

    /**
     * Confirm-stage idempotency coverage under real contention on a shared
     * {@code Booking} row - the case {@code NoOversellIntegrationTest}
     * cannot reach (see class Javadoc) and the multi-seat load complement of
     * {@code BookingIntegrationTest#parallelConfirmCallsOnlyChargeOnce} /
     * {@code #parallelCreateFromHoldCallsForSameHoldOnlyCreateOneBooking}
     * (which cover the same two races, but for a single seat).
     *
     * <p>Run in two genuinely separate phases, not one pass - see this
     * class's task packet for why a single pass would be degenerate: only
     * one thread ever receives a booking id out of phase 1, so racing
     * "whoever gets an id then confirms" in the same pass would just be a
     * single thread confirming alone, and {@code invocationCount() == 1}
     * would be trivially true regardless of whether the row lock actually
     * works. Feeding the one known winning id back to all 8 threads in phase
     * 2 is what makes the assertion meaningful.
     *
     * <p>Phase 1: one {@code Hold} covering all 4 seats; 8 threads race
     * {@code POST /api/bookings} against that single hold id. Exactly one
     * booking is created (the {@code Hold} row lock in {@code
     * createFromHold} serializes the rest, who each observe the
     * already-{@code CONVERTED} hold and reject with 409 {@code
     * HOLD_NOT_ACTIVE}). All 8 threads are fully joined before phase 2
     * starts.
     *
     * <p>Phase 2: all 8 threads race {@code POST
     * /api/bookings/{id}/confirm} against that one known booking id.
     * {@code invocationCount()} is reset immediately beforehand so nothing
     * phase 1 or an earlier test did can pollute the count. {@code
     * BookingService#confirmPayment}'s Javadoc documents that its Booking
     * row lock is held for the whole {@code PaymentService#charge} call;
     * this is the test that makes that serialization observable under real
     * load - whichever thread acquires the lock first performs the one real
     * charge and flips the booking {@code CONFIRMED}, and every other
     * thread blocks on the same row lock, then re-reads the already-{@code
     * CONFIRMED} row and returns it as-is (200) without charging again.
     */
    @Test
    void sharedHoldRaceProducesOneBookingAndOneCharge() throws Exception {
        String token = tokenFor();
        long holdId = createHold(token, eventSeats.stream().map(EventSeat::getId).toList());
        int threadCount = 8;

        // Phase 1: race booking creation for the same hold; join everyone
        // before touching phase 2.
        List<BookingAttemptResult> phase1Results =
                race(threadCount, i -> () -> attemptBooking(token, holdId));

        assertThat(phase1Results).extracting(BookingAttemptResult::status).noneMatch(status -> status == 500);

        List<BookingAttemptResult> phase1Winners =
                phase1Results.stream().filter(r -> r.status() == 201).toList();
        List<BookingAttemptResult> phase1Losers =
                phase1Results.stream().filter(r -> r.status() != 201).toList();
        assertThat(phase1Winners).hasSize(1);
        assertThat(phase1Losers).hasSize(threadCount - 1);
        assertThat(phase1Losers).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.errorCode()).isEqualTo("HOLD_NOT_ACTIVE");
        });

        long bookingId = phase1Winners.get(0).bookingId();

        // Reset defensively right before phase 2 starts: phase 1 never calls
        // PaymentService#charge (booking creation doesn't confirm), so this
        // isn't correcting anything phase 1 itself did - it's a guard
        // against a leftover count from anywhere upstream, so phase 2's
        // == 1 assertion below can never be trivially satisfied by an
        // accident of ordering.
        simulatedPaymentService.resetInvocationCount();

        // Phase 2: a fresh 8-thread race, all targeting the single known
        // booking id captured above.
        List<Integer> phase2Statuses = race(threadCount, i -> () -> attemptConfirm(token, bookingId));

        assertThat(phase2Statuses).noneMatch(status -> status == 500);
        // Every thread gets back 200: the winner performs the real charge
        // and reports CONFIRMED; every other thread blocks on the Booking
        // row lock, then observes the already-CONFIRMED row and returns it
        // as-is - not an error, per confirmPayment's not-PENDING early
        // return.
        assertThat(phase2Statuses).allSatisfy(status -> assertThat(status).isEqualTo(200));

        // The actual idempotency proof: only one of the 8 concurrent confirm
        // calls ever reached PaymentService#charge.
        assertThat(simulatedPaymentService.invocationCount()).isEqualTo(1);

        Booking reloadedBooking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        Payment reloadedPayment = paymentRepository.findByBookingId(bookingId).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        List<EventSeat> reloadedSeats = reloadSeats(eventSeats);
        assertThat(reloadedSeats).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(EventSeatStatus.BOOKED));

        Hold reloadedHold = holdRepository.findById(holdId).orElseThrow();
        assertThat(reloadedHold.getStatus()).isEqualTo(HoldStatus.CONVERTED);

        assertThat(bookingRepository.findByHoldId(holdId))
                .isPresent()
                .get()
                .extracting(Booking::getId)
                .isEqualTo(bookingId);
    }

    /**
     * Runs {@code threadCount} callables built by {@code taskFactory} against
     * a shared start gate, mirroring {@code
     * NoOversellIntegrationTest#race}/{@code
     * HoldIntegrationTest#concurrentHoldRequestsForSameSeatOnlyOneWins}'s
     * latch/executor/futures-with-timeout/{@code finally}-shutdown structure,
     * generified here since this class races two different result shapes
     * ({@link PipelineResult}, {@link BookingAttemptResult}, and plain
     * confirm-status integers).
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

    private BookingAttemptResult attemptBooking(String token, long holdId) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBookingRequest(holdId))))
                .andReturn()
                .getResponse();
        int status = response.getStatus();
        if (status != 201) {
            return new BookingAttemptResult(status, null, errorCode(response));
        }
        long bookingId = objectMapper.readTree(response.getContentAsString()).get("id").asLong();
        return new BookingAttemptResult(status, bookingId, null);
    }

    private int attemptConfirm(String token, long bookingId) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/confirm", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private long createHold(String token, List<Long> seatIds) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldRequest(seatIds))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
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

    private String tokenFor() {
        long userId = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow().getId();
        return jwtService.generateToken(userId, SEEDED_EMAIL);
    }

    @FunctionalInterface
    private interface IntCallableFactory<T> {
        Callable<T> create(int i);
    }

    private record PipelineResult(
            int holdStatus, String holdErrorCode, Long holdId, int bookingStatus, Long bookingId, int confirmStatus) {

        boolean wonHold() {
            return holdStatus == 201;
        }
    }

    private record BookingAttemptResult(int status, Long bookingId, String errorCode) {
    }
}
