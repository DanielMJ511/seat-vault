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
        assertThat(reloadedHold.getStatus()).isEqualTo(com.seatvault.seat_vault.entity.HoldStatus.CONVERTED);

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
}
