package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.seatvault.seat_vault.config.HoldProperties;
import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.dto.HoldRequest;
import com.seatvault.seat_vault.entity.Event;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import com.seatvault.seat_vault.entity.Hold;
import com.seatvault.seat_vault.entity.HoldStatus;
import com.seatvault.seat_vault.entity.User;
import com.seatvault.seat_vault.repository.EventRepository;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.HoldRepository;
import com.seatvault.seat_vault.repository.UserRepository;
import com.seatvault.seat_vault.security.JwtService;
import com.seatvault.seat_vault.service.HoldSweepService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
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
 * End-to-end coverage of the {@code /api/holds/**} surface against a real
 * Postgres/Redis stack, including the seeded {@code Riverside Jazz Night}
 * seat grid from {@code db/seed}. Riverside's seats are used exclusively
 * (rather than the Grand Hall/Gala seats {@link EventSeatIntegrationTest}
 * asserts against) so this class's one genuinely non-transactional test (the
 * concurrency race, which needs real independent per-thread transactions and
 * therefore really commits) can never contaminate seed data another test
 * class depends on. Every other test here mirrors {@code
 * EventSeatIntegrationTest}'s {@code @Transactional} pattern: MockMvc
 * dispatches in-process on the test thread, so the mutation and the
 * assertion share one transaction that's rolled back automatically.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class HoldIntegrationTest {

    private static final String RIVERSIDE_EVENT_NAME = "Riverside Jazz Night";
    private static final String GALA_EVENT_NAME = "Opening Night Gala";
    private static final String SEEDED_EMAIL = "alice@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventSeatRepository eventSeatRepository;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private HoldProperties holdProperties;

    @Autowired
    private HoldSweepService holdSweepService;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void createHoldOnTwoAvailableSeatsFlipsThemToHeldAndSnapshotsPrice() throws Exception {
        List<EventSeat> riversideSeats = riversideSeats();
        EventSeat seatOne = riversideSeats.get(0);
        EventSeat seatTwo = riversideSeats.get(1);
        String token = tokenFor(aliceId(), SEEDED_EMAIL);

        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdRequestJson(seatOne.getId(), seatTwo.getId())))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(notNullValue()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(equalTo("ACTIVE")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.seats.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        for (JsonNode seatJson : json.get("seats")) {
            long eventSeatId = seatJson.get("eventSeatId").asLong();
            EventSeat expected = eventSeatId == seatOne.getId() ? seatOne : seatTwo;
            assertThat(seatJson.get("priceSnapshot").decimalValue())
                    .isEqualByComparingTo(expected.getPrice());
        }

        EventSeat reloadedOne = eventSeatRepository.findById(seatOne.getId()).orElseThrow();
        EventSeat reloadedTwo = eventSeatRepository.findById(seatTwo.getId()).orElseThrow();
        assertThat(reloadedOne.getStatus()).isEqualTo(EventSeatStatus.HELD);
        assertThat(reloadedTwo.getStatus()).isEqualTo(EventSeatStatus.HELD);
        assertThat(reloadedOne.getCurrentHold()).isNotNull();
        assertThat(reloadedOne.getCurrentHold().getId()).isEqualTo(reloadedTwo.getCurrentHold().getId());
    }

    @Test
    @Transactional
    void createHoldExceedingMaxSeatsPerHoldIsRejected() throws Exception {
        List<EventSeat> riversideSeats = riversideSeats();
        int tooMany = holdProperties.maxSeatsPerHold() + 1;
        Long[] seatIds = riversideSeats.subList(2, 2 + tooMany).stream()
                .map(EventSeat::getId)
                .toArray(Long[]::new);
        String token = tokenFor(aliceId(), SEEDED_EMAIL);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdRequestJson(seatIds)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("TOO_MANY_SEATS")));
    }

    @Test
    @Transactional
    void createHoldSpanningMultipleEventsIsRejected() throws Exception {
        EventSeat riversideSeat = riversideSeats().get(15);
        EventSeat galaSeat = galaSeats().get(0);
        String token = tokenFor(aliceId(), SEEDED_EMAIL);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdRequestJson(riversideSeat.getId(), galaSeat.getId())))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("MULTIPLE_EVENTS_IN_HOLD")));

        EventSeat reloadedRiverside = eventSeatRepository.findById(riversideSeat.getId()).orElseThrow();
        EventSeat reloadedGala = eventSeatRepository.findById(galaSeat.getId()).orElseThrow();
        assertThat(reloadedRiverside.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(reloadedGala.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
    }

    @Test
    @Transactional
    void createHoldOnAnAlreadyBookedSeatIsRejectedWithDistinctCodeFromHeld() throws Exception {
        EventSeat seat = riversideSeats().get(16);
        seat.setStatus(EventSeatStatus.BOOKED);
        eventSeatRepository.saveAndFlush(seat);
        String token = tokenFor(aliceId(), SEEDED_EMAIL);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdRequestJson(seat.getId())))
                .andExpect(MockMvcResultMatchers.status().isConflict())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("SEAT_ALREADY_BOOKED")));
    }

    /**
     * Deliberately not {@code @Transactional}: 20 threads each need their own
     * real transaction/connection racing for the same Postgres row lock
     * ({@link EventSeatRepository#findByIdForUpdate(Long)}), which a single
     * test-managed transaction bound to this method's thread cannot provide.
     * The target seat (index 11 in the Riverside grid) is used by no other
     * test in this class or elsewhere in the suite, so this test's real,
     * permanent commit can never contaminate another test's assertions.
     */
    @Test
    void concurrentHoldRequestsForSameSeatOnlyOneWins() throws Exception {
        EventSeat targetSeat = riversideSeats().get(11);
        String token = tokenFor(aliceId(), SEEDED_EMAIL);
        String requestBody = holdRequestJson(targetSeat.getId());

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<Integer>> tasks = IntStream.range(0, threadCount)
                .<Callable<Integer>>mapToObj(i -> () -> {
                    startGate.await();
                    return mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
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

        EventSeat reloaded = eventSeatRepository.findById(targetSeat.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EventSeatStatus.HELD);
        assertThat(reloaded.getCurrentHold()).isNotNull();

        // getCurrentHold() above is a lazy proxy and this test isn't
        // @Transactional (each racing thread needs its own real
        // transaction), so re-fetch the winning Hold through its own
        // repository call - a fresh, fully-initialized transactional read -
        // rather than touching the detached proxy directly.
        Hold winningHold = holdRepository.findById(reloaded.getCurrentHold().getId()).orElseThrow();
        assertThat(winningHold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    @Transactional
    void ownerCanReleaseTheirOwnActiveHoldButAnotherUserCannot() throws Exception {
        EventSeat seat = riversideSeats().get(12);
        String aliceToken = tokenFor(aliceId(), SEEDED_EMAIL);

        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdRequestJson(seat.getId())))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long holdId = objectMapper.readTree(body).get("id").asLong();

        User bob = userRepository.save(User.builder()
                .email("bob-hold-test@example.com")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .build());
        String bobToken = tokenFor(bob.getId(), bob.getEmail());

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/holds/{id}", holdId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("HOLD_NOT_FOUND")));

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/holds/{id}", holdId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(MockMvcResultMatchers.status().isNoContent());

        EventSeat reloadedSeat = eventSeatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloadedSeat.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(reloadedSeat.getCurrentHold()).isNull();

        Hold reloadedHold = holdRepository.findById(holdId).orElseThrow();
        assertThat(reloadedHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
    }

    @Test
    @Transactional
    void lazilyExpiredHoldLetsANewHoldRequestWinAndReconcilesTheStaleHold() throws Exception {
        EventSeat seat = riversideSeats().get(13);
        String token = tokenFor(aliceId(), SEEDED_EMAIL);

        String firstBody = mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdRequestJson(seat.getId())))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long staleHoldId = objectMapper.readTree(firstBody).get("id").asLong();

        Hold staleHold = holdRepository.findById(staleHoldId).orElseThrow();
        staleHold.setExpiresAt(Instant.now().minusSeconds(60));
        entityManager.flush();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdRequestJson(seat.getId())))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(org.hamcrest.Matchers.not(equalTo(staleHoldId))));

        Hold reloadedStaleHold = holdRepository.findById(staleHoldId).orElseThrow();
        assertThat(reloadedStaleHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);

        EventSeat reloadedSeat = eventSeatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloadedSeat.getStatus()).isEqualTo(EventSeatStatus.HELD);
        assertThat(reloadedSeat.getCurrentHold().getId()).isNotEqualTo(staleHoldId);
    }

    @Test
    @Transactional
    void sweepReclaimsAnExpiredHeldSeatAndExpiresItsHold() throws Exception {
        EventSeat seat = riversideSeats().get(14);
        String token = tokenFor(aliceId(), SEEDED_EMAIL);

        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdRequestJson(seat.getId())))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long holdId = objectMapper.readTree(body).get("id").asLong();

        Hold hold = holdRepository.findById(holdId).orElseThrow();
        hold.setExpiresAt(Instant.now().minusSeconds(60));
        entityManager.flush();

        holdSweepService.sweepExpiredHolds();
        // The sweep's @Modifying bulk updates run as raw SQL against this
        // same transaction's connection without clearing the persistence
        // context (matching the plan's exact repository query shape), so
        // clear it here to avoid re-reading stale first-level-cache entities.
        entityManager.clear();

        EventSeat reloadedSeat = eventSeatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloadedSeat.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(reloadedSeat.getCurrentHold()).isNull();

        Hold reloadedHold = holdRepository.findById(holdId).orElseThrow();
        assertThat(reloadedHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
    }

    private List<EventSeat> riversideSeats() {
        Event riverside = eventRepository.findAllWithVenueOrderByStartsAtAsc().stream()
                .filter(e -> e.getName().equals(RIVERSIDE_EVENT_NAME))
                .findFirst()
                .orElseThrow();
        return eventSeatRepository.findByEventIdWithSeatAndHold(riverside.getId());
    }

    /**
     * Only used, read-only, by {@code createHoldSpanningMultipleEventsIsRejected}
     * (which is {@code @Transactional} and asserts nothing about this event's
     * seats afterward beyond "still AVAILABLE") - safe alongside {@code
     * EventSeatIntegrationTest}'s own Gala-seat assertions.
     */
    private List<EventSeat> galaSeats() {
        Event gala = eventRepository.findAllWithVenueOrderByStartsAtAsc().stream()
                .filter(e -> e.getName().equals(GALA_EVENT_NAME))
                .findFirst()
                .orElseThrow();
        return eventSeatRepository.findByEventIdWithSeatAndHold(gala.getId());
    }

    private long aliceId() {
        return userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow().getId();
    }

    private String tokenFor(long userId, String email) {
        return jwtService.generateToken(userId, email);
    }

    private String holdRequestJson(Long... eventSeatIds) throws Exception {
        return objectMapper.writeValueAsString(new HoldRequest(List.of(eventSeatIds)));
    }
}
