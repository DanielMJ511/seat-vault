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
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
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
 *
 * <p><b>Why the two sweep-metric tests assert deltas, never absolute values
 * (T-005).</b> The {@code seatvault.sweep.*} summaries this class reads are
 * <em>not</em> private to it, and that was measured rather than assumed. This
 * class's {@code @SpringBootTest @AutoConfigureMockMvc
 * @Import(TestcontainersConfig.class) @ActiveProfiles("test")
 * @TestPropertySource(...seed)} annotation set is byte-for-byte shared by ten
 * classes - {@code AuthIntegrationTest}, {@code
 * BookingConfirmLoadIntegrationTest}, {@code BookingIntegrationTest}, {@code
 * EventIntegrationTest}, {@code EventSeatIntegrationTest}, this class, {@code
 * NoOversellIntegrationTest}, {@code SecurityConfigIntegrationTest}, {@code
 * VenueIntegrationTest} and {@code GlobalExceptionHandlerIntegrationTest} -
 * so all ten produce the same {@code MergedContextConfiguration} cache key
 * and Spring hands them <em>one</em> application context, hence one {@code
 * MeterRegistry}, one {@code HoldSweepService}, and one Postgres container.
 * Verified by printing the identity hashes of the injected {@code
 * ApplicationContext} and {@code MeterRegistry} alongside the live JDBC URL
 * during a full suite run: identical for all of them.
 *
 * <p>The consequence: {@code HoldSweepService}'s two sweep metrics in this
 * registry are not private to this class's two direct {@code
 * sweepExpiredHolds()} calls (see below) even though, under the {@code test}
 * profile, {@code seatvault.sweep.scheduling.enabled=false} (T-001, {@code
 * config/SchedulingConfig.java}) means no live 30-second timer runs anywhere
 * in this shared context - a stale earlier version of this comment cited that
 * timer directly as the reason for diffing, which is no longer true and would
 * mislead a reader into thinking this test tolerates a hazard that no longer
 * exists. The real, current reason is JUnit gives no ordering guarantee
 * between this class's own {@code sweepReclaimsAnExpiredHeldSeatAndExpiresItsHold}
 * and {@code idleSweepRecordsZeroRatherThanSkippingTheMetric}: both call
 * {@code holdSweepService.sweepExpiredHolds()} directly against this one
 * shared {@code MeterRegistry}, so whichever runs first has already advanced
 * both summaries before the other one's assertion reads them. Any future
 * direct caller of {@code sweepExpiredHolds()} sharing this context adds to
 * the same risk, which is why the registry-sharing property above is worth
 * keeping documented even though nothing external writes into it today.
 *
 * <p>That is exactly why {@code #max()} is not asserted on anywhere in this
 * class. {@code DistributionSummary#max()} is a {@code TimeWindowMax}: a
 * rolling window (about two minutes) over everything <em>anyone</em> recorded,
 * so {@code assertThat(summary.max()).isEqualTo(1.0)} fails the moment
 * anything else sharing this registry - including this class's own other
 * direct-call test - is the largest thing in the window - a failure that says
 * nothing about whether the recording under test is correct. {@code
 * #totalAmount()} and {@code #count()} are strictly cumulative and monotonic,
 * so before/after deltas around the call under test are immune to that noise
 * while still going to zero if the recording is removed.
 *
 * <p>Note that a separate context - {@code
 * HoldSweepSeatLockOrderIntegrationTest}, whose annotations differ only by
 * lacking {@code @AutoConfigureMockMvc} and whose {@code
 * PropertyMappingContextCustomizer} therefore splits the cache key - is
 * <em>not</em> a noise source here despite also committing expired holds: a
 * distinct context gets a distinct registry <em>and</em> its own Postgres
 * container ({@code PostgresTestcontainersConfig} declares the container as a
 * plain {@code @Bean} with no Testcontainers reuse), so nothing that context's
 * own direct {@code sweepExpiredHolds()} call commits or records is visible to
 * this context's meters.
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

    @Autowired
    private MeterRegistry meterRegistry;

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

        // T-005: read the two sweep summaries before and after, and diff.
        // Both reads must be deltas rather than absolute values - see this
        // class's Javadoc for the shared-registry noise source that makes any
        // absolute assertion here unsafe. #totalAmount is specifically the
        // safe statistic to diff: it is strictly cumulative, so it is
        // order-insensitive and this class's other direct-call test running
        // before or after this one (which legitimately records 0) cannot
        // move it. #max is deliberately NOT
        // asserted on: it is Micrometer's TimeWindowMax, a rolling window
        // that reports the largest value recorded in roughly the last two
        // minutes by anyone sharing this registry, so any assertion on it -
        // absolute or delta - is a bet on what the rest of the suite happened
        // to be doing. It would also add nothing: the totalAmount deltas
        // below already pin the exact value this sweep recorded.
        DistributionSummary seatsSummary = meterRegistry.get("seatvault.sweep.seats.reclaimed").summary();
        DistributionSummary holdsSummary = meterRegistry.get("seatvault.sweep.holds.expired").summary();
        double seatsTotalBefore = seatsSummary.totalAmount();
        double holdsTotalBefore = holdsSummary.totalAmount();

        holdSweepService.sweepExpiredHolds();
        // The sweep now runs three statements on this same transaction's
        // connection (T-001/#16): a native locking id projection, then two
        // @Modifying bulk updates. None of the three populate this
        // transaction's persistence context - the projection is a scalar
        // native query (ADR-0010) and the bulk updates are raw SQL - so
        // Hibernate's first-level cache still doesn't know any of it
        // happened. Clear it here to avoid re-reading stale cached entities.
        entityManager.clear();

        EventSeat reloadedSeat = eventSeatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloadedSeat.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(reloadedSeat.getCurrentHold()).isNull();

        Hold reloadedHold = holdRepository.findById(holdId).orElseThrow();
        assertThat(reloadedHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);

        assertThat(seatsSummary.totalAmount() - seatsTotalBefore)
                .as("the sweep must record the one seat it reclaimed into "
                        + "seatvault.sweep.seats.reclaimed")
                .isEqualTo(1.0);
        assertThat(holdsSummary.totalAmount() - holdsTotalBefore)
                .as("the sweep must record the one hold it expired into "
                        + "seatvault.sweep.holds.expired")
                .isEqualTo(1.0);
    }

    /**
     * The other half of T-005's zero-recording decision: an idle sweep -
     * nothing expired, nothing to do - must still record {@code 0} rather
     * than skip the call, because {@code HoldSweepService} takes {@code
     * expiredSeatIds.size()} on a branch where the bulk {@code UPDATE} is
     * skipped entirely and it would be easy to skip the metric with it.
     *
     * <p>Asserted via {@code #count}, not {@code #totalAmount}: a recorded
     * {@code 0} moves {@code count} but by definition never moves {@code
     * totalAmount}, so {@code totalAmount} alone cannot distinguish "recorded
     * a 0" from "recorded nothing at all". The {@code count} delta is
     * asserted as "at least 1" rather than "exactly 1" so this class's other
     * direct-call test recording into the same registry around the same time
     * (see this class's Javadoc) cannot fail it - and that tolerance costs
     * nothing, because every call goes through the same single {@code
     * SweepMetrics#recordSweep} call site. Delete that call and no invocation
     * on any thread can move {@code count} at all, so the delta becomes
     * deterministically 0 and this fails.
     */
    @Test
    @Transactional
    void idleSweepRecordsZeroRatherThanSkippingTheMetric() {
        DistributionSummary seatsSummary = meterRegistry.get("seatvault.sweep.seats.reclaimed").summary();
        DistributionSummary holdsSummary = meterRegistry.get("seatvault.sweep.holds.expired").summary();
        long seatsCountBefore = seatsSummary.count();
        long holdsCountBefore = holdsSummary.count();
        double seatsTotalBefore = seatsSummary.totalAmount();
        double holdsTotalBefore = holdsSummary.totalAmount();

        holdSweepService.sweepExpiredHolds();

        assertThat(seatsSummary.count() - seatsCountBefore)
                .as("an idle sweep must still record a value into "
                        + "seatvault.sweep.seats.reclaimed, not skip the metric")
                .isGreaterThanOrEqualTo(1);
        assertThat(holdsSummary.count() - holdsCountBefore)
                .as("an idle sweep must still record a value into "
                        + "seatvault.sweep.holds.expired, not skip the metric")
                .isGreaterThanOrEqualTo(1);
        assertThat(seatsSummary.totalAmount())
                .as("the value an idle sweep records must be 0")
                .isEqualTo(seatsTotalBefore);
        assertThat(holdsSummary.totalAmount())
                .as("the value an idle sweep records must be 0")
                .isEqualTo(holdsTotalBefore);
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
