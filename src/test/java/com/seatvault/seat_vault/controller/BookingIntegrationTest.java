package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

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
import com.seatvault.seat_vault.entity.User;
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
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end coverage of the {@code /api/bookings/**} surface against a real
 * Postgres/Redis stack. Rather than sharing the seeded Riverside/Gala event
 * data ({@link HoldIntegrationTest}/{@code EventSeatIntegrationTest} already
 * reserve slices of it, one of them via a real, permanent commit), each test
 * here builds its own private Venue/Seats/Event/EventSeats in
 * {@link #setUp()} - a fresh set of ids every run, so there's no bookkeeping
 * to coordinate across test classes regardless of which tests here commit for
 * real vs. roll back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class BookingIntegrationTest {

    private static final String SEEDED_EMAIL = "alice@example.com";

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SimulatedPaymentServiceImpl simulatedPaymentService;

    @Autowired
    private EntityManager entityManager;

    private List<EventSeat> eventSeats;

    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("Booking Test Venue " + System.nanoTime())
                .address("1 Test Way")
                .build());
        Event event = eventRepository.save(Event.builder()
                .venue(venue)
                .name("Booking Test Event")
                // Deliberately well after both seeded events' startsAt
                // (2026-09-15/2026-09-22): the one non-@Transactional test in
                // this class (parallelConfirmCallsOnlyChargeOnce) really
                // commits this Event, and EventIntegrationTest asserts the
                // seeded events are first/second when ordered by startsAt -
                // an earlier date here would silently break that ordering
                // assertion for every other test class in the suite.
                .startsAt(Instant.now().plus(400, ChronoUnit.DAYS))
                .build());

        eventSeats = IntStream.range(0, 2)
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
                            .price(new BigDecimal("40.00"))
                            .build());
                })
                .toList();
    }

    @AfterEach
    void tearDown() {
        simulatedPaymentService.resetForcedOutcome();
        simulatedPaymentService.resetInvocationCount();
    }

    @Test
    @Transactional
    void holdConvertsToBookingThenConfirmedPaymentFlipsSeatsToBooked() throws Exception {
        String token = tokenFor();
        long holdId = createHold(token, eventSeats.get(0).getId(), eventSeats.get(1).getId());

        String createBody = mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBookingRequest(holdId))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("PENDING")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.seats.length()").value(2))
                .andExpect(MockMvcResultMatchers.jsonPath("$.payment.status").value(equalTo("PENDING")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode createJson = objectMapper.readTree(createBody);
        long bookingId = createJson.get("id").asLong();
        assertThat(createJson.get("payment").get("amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("80.00"));

        for (EventSeat eventSeat : eventSeats) {
            EventSeat reloaded = eventSeatRepository.findById(eventSeat.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(EventSeatStatus.BOOKED);
        }
        Hold reloadedHold = holdRepository.findById(holdId).orElseThrow();
        assertThat(reloadedHold.getStatus()).isEqualTo(HoldStatus.CONVERTED);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/confirm", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("CONFIRMED")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.payment.status").value(equalTo("SUCCEEDED")));

        for (EventSeat eventSeat : eventSeats) {
            EventSeat reloaded = eventSeatRepository.findById(eventSeat.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(EventSeatStatus.BOOKED);
        }
    }

    @Test
    @Transactional
    void createBookingFromLazilyExpiredHoldIsRejected() throws Exception {
        String token = tokenFor();
        long holdId = createHold(token, eventSeats.get(0).getId());

        Hold hold = holdRepository.findById(holdId).orElseThrow();
        hold.setExpiresAt(Instant.now().minusSeconds(60));
        entityManager.flush();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBookingRequest(holdId))))
                .andExpect(MockMvcResultMatchers.status().isConflict())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("HOLD_NOT_ACTIVE")));

        assertThat(bookingRepository.findByHoldId(holdId)).isEmpty();
    }

    @Test
    @Transactional
    void simulatedDeclineReleasesSeatImmediately() throws Exception {
        simulatedPaymentService.forceNextOutcome(PaymentStatus.FAILED);
        String token = tokenFor();
        long holdId = createHold(token, eventSeats.get(0).getId());
        long bookingId = createBooking(token, holdId);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/confirm", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("FAILED")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.payment.status").value(equalTo("FAILED")));

        EventSeat reloaded = eventSeatRepository.findById(eventSeats.get(0).getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(reloaded.getCurrentHold()).isNull();

        Booking reloadedBooking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.FAILED);
    }

    /**
     * Deliberately not {@code @Transactional}, mirroring {@code
     * HoldIntegrationTest#concurrentHoldRequestsForSameSeatOnlyOneWins}: each
     * racing confirm call needs its own real Postgres transaction/connection
     * contending for the same Booking row lock, which a single
     * test-managed transaction bound to this method's thread cannot provide.
     * This test's own fresh Venue/Event/EventSeat fixture (see {@link
     * #setUp()}) means its real, permanent commit can never contaminate
     * another test.
     */
    @Test
    void parallelConfirmCallsOnlyChargeOnce() throws Exception {
        String token = tokenFor();
        long holdId = createHold(token, eventSeats.get(0).getId());
        long bookingId = createBooking(token, holdId);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<Integer>> tasks = IntStream.range(0, threadCount)
                .<Callable<Integer>>mapToObj(i -> () -> {
                    startGate.await();
                    return mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/confirm", bookingId)
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                })
                .toList();

        try {
            List<Future<Integer>> futures = tasks.stream().map(executor::submit).toList();
            startGate.countDown();

            List<Integer> statuses = futures.stream().map(f -> {
                try {
                    return f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            AtomicInteger ok = new AtomicInteger();
            for (int status : statuses) {
                if (status == 200) {
                    ok.incrementAndGet();
                }
            }
            assertThat(ok.get()).isEqualTo(threadCount);
        } finally {
            executor.shutdownNow();
        }

        assertThat(simulatedPaymentService.invocationCount()).isEqualTo(1);

        Booking reloadedBooking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        Payment reloadedPayment = paymentRepository.findByBookingId(bookingId).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    /**
     * Regression test for a race a code review caught: {@code
     * HoldService#releaseHold} and {@code BookingService#createFromHold} both
     * used to read a Hold via a plain unlocked {@code findById}, so the loser
     * of a race between "release this hold" and "convert this hold into a
     * booking" could blindly overwrite the winner's just-committed status
     * with its own stale one - e.g. a fully paid, CONVERTED hold silently
     * reported back as EXPIRED. Both methods now lock the Hold row ({@code
     * HoldRepository#findByIdForUpdate}), so the loser here must re-read the
     * committed status and reject cleanly instead.
     *
     * <p>Since T-007 both take that lock <em>after</em> their seat locks
     * rather than before, so the two calls serialize on the seat row first and
     * on the Hold row second. Either row is enough to produce exactly one
     * winner, which is all this test asserts - what changed is only where the
     * loser waits. Deliberately
     * not {@code @Transactional} for the same reason as {@code
     * parallelConfirmCallsOnlyChargeOnce}: each racing call needs its own
     * real transaction contending for the same Hold row lock.
     */
    @Test
    void concurrentReleaseAndCreateFromHoldOnSameHoldResolvesConsistently() throws Exception {
        String token = tokenFor();
        long holdId = createHold(token, eventSeats.get(0).getId());

        CountDownLatch startGate = new CountDownLatch(1);
        Callable<Integer> releaseCall = () -> {
            startGate.await();
            return mockMvc.perform(MockMvcRequestBuilders.delete("/api/holds/{id}", holdId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };
        Callable<Integer> bookCall = () -> {
            startGate.await();
            return mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new CreateBookingRequest(holdId))))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        int releaseStatus;
        int bookStatus;
        try {
            Future<Integer> releaseFuture = executor.submit(releaseCall);
            Future<Integer> bookFuture = executor.submit(bookCall);
            startGate.countDown();
            releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            bookStatus = bookFuture.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        boolean releaseWon = releaseStatus == 204;
        boolean bookWon = bookStatus == 201;
        // Exactly one side wins - the Hold row lock serializes them, so
        // whichever transaction commits first leaves the other to observe a
        // no-longer-ACTIVE Hold and reject with 409.
        assertThat(releaseWon ^ bookWon).isTrue();
        if (releaseWon) {
            assertThat(bookStatus).isEqualTo(409);
        } else {
            assertThat(releaseStatus).isEqualTo(409);
        }

        Hold reloadedHold = holdRepository.findById(holdId).orElseThrow();
        EventSeat reloadedSeat = eventSeatRepository.findById(eventSeats.get(0).getId()).orElseThrow();
        if (releaseWon) {
            assertThat(reloadedHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
            assertThat(reloadedSeat.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
            assertThat(bookingRepository.findByHoldId(holdId)).isEmpty();
        } else {
            assertThat(reloadedHold.getStatus()).isEqualTo(HoldStatus.CONVERTED);
            assertThat(reloadedSeat.getStatus()).isEqualTo(EventSeatStatus.BOOKED);
            assertThat(bookingRepository.findByHoldId(holdId)).isPresent();
        }
    }

    /**
     * Mirrors {@code HoldIntegrationTest#concurrentHoldRequestsForSameSeatOnlyOneWins}
     * for the booking-creation path: N concurrent {@code createFromHold}
     * calls for the *same* hold must produce exactly one Booking. The losers
     * are rejected at the top-level ACTIVE check - after taking the seat locks
     * (T-007's ordering) but before the per-seat loop - rather than deep
     * inside it, which is still exactly the "only one side effect" guarantee
     * this test is here to pin down. Deliberately not
     * {@code @Transactional} for the same reason as the other concurrency
     * tests in this class.
     */
    @Test
    void parallelCreateFromHoldCallsForSameHoldOnlyCreateOneBooking() throws Exception {
        String token = tokenFor();
        long holdId = createHold(token, eventSeats.get(0).getId());

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<Integer>> tasks = IntStream.range(0, threadCount)
                .<Callable<Integer>>mapToObj(i -> () -> {
                    startGate.await();
                    return mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new CreateBookingRequest(holdId))))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                })
                .toList();

        try {
            List<Future<Integer>> futures = tasks.stream().map(executor::submit).toList();
            startGate.countDown();

            List<Integer> statuses = futures.stream().map(f -> {
                try {
                    return f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            AtomicInteger created = new AtomicInteger();
            AtomicInteger conflicted = new AtomicInteger();
            for (int status : statuses) {
                if (status == 201) {
                    created.incrementAndGet();
                } else if (status == 409) {
                    conflicted.incrementAndGet();
                }
            }
            assertThat(created.get()).isEqualTo(1);
            assertThat(conflicted.get()).isEqualTo(threadCount - 1);
        } finally {
            executor.shutdownNow();
        }

        Hold reloadedHold = holdRepository.findById(holdId).orElseThrow();
        assertThat(reloadedHold.getStatus()).isEqualTo(HoldStatus.CONVERTED);
        EventSeat reloadedSeat = eventSeatRepository.findById(eventSeats.get(0).getId()).orElseThrow();
        assertThat(reloadedSeat.getStatus()).isEqualTo(EventSeatStatus.BOOKED);
        assertThat(bookingRepository.findByHoldId(holdId)).isPresent();
    }

    @Test
    @Transactional
    void listDetailAndCancelHappyPathsWork() throws Exception {
        String token = tokenFor();
        long holdId = createHold(token, eventSeats.get(0).getId(), eventSeats.get(1).getId());
        long bookingId = createBooking(token, holdId);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/confirm", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("CONFIRMED")));

        // List: other test methods in this class (the non-@Transactional
        // concurrency ones) leave real, committed bookings behind for this
        // same seeded user, so assert this booking is present rather than
        // asserting an exact array length.
        String listBody = mockMvc.perform(MockMvcRequestBuilders.get("/api/bookings/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode listJson = objectMapper.readTree(listBody);
        JsonNode listedBooking = null;
        for (JsonNode node : listJson) {
            if (node.get("id").asLong() == bookingId) {
                listedBooking = node;
                break;
            }
        }
        assertThat(listedBooking).isNotNull();
        assertThat(listedBooking.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(listedBooking.get("seats").size()).isEqualTo(2);

        // Detail
        mockMvc.perform(MockMvcRequestBuilders.get("/api/bookings/{id}", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(bookingId))
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("CONFIRMED")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.payment.status").value(equalTo("SUCCEEDED")));

        // Cancel
        mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/cancel", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("CANCELLED")));

        for (EventSeat eventSeat : eventSeats) {
            EventSeat reloaded = eventSeatRepository.findById(eventSeat.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
            assertThat(reloaded.getCurrentHold()).isNull();
        }

        mockMvc.perform(MockMvcRequestBuilders.get("/api/bookings/{id}", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("CANCELLED")));
    }

    @Test
    @Transactional
    void cancelOfNonConfirmedBookingIsRejectedWith409() throws Exception {
        String token = tokenFor();
        long holdId = createHold(token, eventSeats.get(0).getId());
        long bookingId = createBooking(token, holdId);

        // Deliberately left PENDING - never confirmed.
        mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/cancel", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isConflict())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("BOOKING_NOT_CONFIRMED")));

        Booking reloadedBooking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.PENDING);
        EventSeat reloadedSeat = eventSeatRepository.findById(eventSeats.get(0).getId()).orElseThrow();
        assertThat(reloadedSeat.getStatus()).isEqualTo(EventSeatStatus.BOOKED);
    }

    @Test
    @Transactional
    void cancellationMakesSeatImmediatelyHoldableByAnotherUser() throws Exception {
        String aliceToken = tokenFor();
        long holdId = createHold(aliceToken, eventSeats.get(0).getId());
        long bookingId = createBooking(aliceToken, holdId);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/confirm", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("CONFIRMED")));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/cancel", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("CANCELLED")));

        User bob = userRepository.save(User.builder()
                .email("bob-booking-test@example.com")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .build());
        String bobToken = tokenFor(bob.getId(), bob.getEmail());

        String holdBody = mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new HoldRequest(List.of(eventSeats.get(0).getId())))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long bobHoldId = objectMapper.readTree(holdBody).get("id").asLong();

        Hold reloadedHold = holdRepository.findById(bobHoldId).orElseThrow();
        assertThat(reloadedHold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(reloadedHold.getUser().getId()).isEqualTo(bob.getId());
        EventSeat reloadedSeat = eventSeatRepository.findById(eventSeats.get(0).getId()).orElseThrow();
        assertThat(reloadedSeat.getStatus()).isEqualTo(EventSeatStatus.HELD);
    }

    /**
     * Mirrors {@code concurrentReleaseAndCreateFromHoldOnSameHoldResolvesConsistently}:
     * two racing {@code cancel} calls on the same CONFIRMED booking must
     * serialize on the Booking row lock ({@code cancel}'s Javadoc), leaving
     * exactly one 200/CANCELLED winner and one 409 loser - not merely "no
     * crash and no double-release." Deliberately not {@code @Transactional}
     * so each racing call gets its own real transaction contending for the
     * same Booking row lock.
     */
    @Test
    void concurrentDoubleCancelOnSameBookingExactlyOneWins() throws Exception {
        String token = tokenFor();
        long holdId = createHold(token, eventSeats.get(0).getId());
        long bookingId = createBooking(token, holdId);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/confirm", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("CONFIRMED")));

        int callCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(callCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<Integer>> tasks = IntStream.range(0, callCount)
                .<Callable<Integer>>mapToObj(i -> () -> {
                    startGate.await();
                    return mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings/{id}/cancel", bookingId)
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                })
                .toList();

        List<Integer> statuses;
        try {
            List<Future<Integer>> futures = tasks.stream().map(executor::submit).toList();
            startGate.countDown();
            statuses = futures.stream()
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

        long wins = statuses.stream().filter(status -> status == 200).count();
        long losses = statuses.stream().filter(status -> status == 409).count();
        assertThat(wins).isEqualTo(1);
        assertThat(losses).isEqualTo(callCount - 1);

        Booking reloadedBooking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(reloadedBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        EventSeat reloadedSeat = eventSeatRepository.findById(eventSeats.get(0).getId()).orElseThrow();
        assertThat(reloadedSeat.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(reloadedSeat.getCurrentHold()).isNull();
    }

    private long createHold(String token, Long... seatIds) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldRequest(List.of(seatIds)))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(notNullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long createBooking(String token, long holdId) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBookingRequest(holdId))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private String tokenFor() {
        long userId = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow().getId();
        return jwtService.generateToken(userId, SEEDED_EMAIL);
    }

    private String tokenFor(long userId, String email) {
        return jwtService.generateToken(userId, email);
    }
}
