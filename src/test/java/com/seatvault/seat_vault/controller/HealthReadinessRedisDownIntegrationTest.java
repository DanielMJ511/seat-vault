package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatvault.seat_vault.config.BrokenRedisTestConfig;
import com.seatvault.seat_vault.config.PostgresTestcontainersConfig;
import com.seatvault.seat_vault.dto.HoldRequest;
import com.seatvault.seat_vault.entity.Event;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import com.seatvault.seat_vault.entity.Seat;
import com.seatvault.seat_vault.entity.Venue;
import com.seatvault.seat_vault.repository.EventRepository;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.SeatRepository;
import com.seatvault.seat_vault.repository.UserRepository;
import com.seatvault.seat_vault.repository.VenueRepository;
import com.seatvault.seat_vault.security.JwtService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes ADR-0013's central claim falsifiable end to end, at the HTTP surface
 * rather than by inspecting configuration: with Redis genuinely unreachable,
 * {@code /actuator/health/readiness} must still report {@code UP} (so an
 * orchestrator never evicts a functional instance - ADR-0001), while the
 * parent {@code /actuator/health} aggregate must still report the Redis
 * indicator's true, {@code DOWN} state to an authenticated caller rather than
 * silently omitting it or forcing it to {@code UP}. This is the runtime
 * counterpart to {@link HoldRedisUnavailableRaceIntegrationTest}, which
 * proves the same outage does not break hold-stage correctness; this class
 * proves it does not break the health signal an orchestrator actually reads,
 * and combines that with a real hold request succeeding in the same outage to
 * show the two facts hold in the same running instance, not in isolation.
 *
 * <p>Deliberately mirrors {@link HoldRedisUnavailableRaceIntegrationTest}'s
 * exact {@code @Import}/{@code @ActiveProfiles}/{@code @TestPropertySource}
 * set (real Postgres via {@link PostgresTestcontainersConfig}, Redis pointed
 * at a dead port via {@link BrokenRedisTestConfig} - never the shared {@code
 * TestcontainersConfig}, and never stopping the shared Testcontainers Redis
 * container, since that is shared across the cached context used by every
 * other integration test in the suite and stopping it would break all of
 * them) so that Spring's context cache can reuse the same application context
 * those tests already pay for, rather than spinning up a second, independent
 * Postgres container for an identical configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, BrokenRedisTestConfig.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class HealthReadinessRedisDownIntegrationTest {

    private static final String SEEDED_EMAIL = "alice@example.com";
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
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private EventSeat eventSeat;

    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("Health-Redis-Down Test Venue " + System.nanoTime())
                .address("1 Test Way")
                .build());
        Event event = eventRepository.save(Event.builder()
                .venue(venue)
                .name("Health-Redis-Down Test Event")
                .startsAt(Instant.now().plus(400, ChronoUnit.DAYS))
                .build());
        Seat seat = seatRepository.save(
                Seat.builder().venue(venue).section("A").rowLabel("A").seatNumber(1).build());
        eventSeat = eventSeatRepository.save(EventSeat.builder()
                .event(event)
                .seat(seat)
                .status(EventSeatStatus.AVAILABLE)
                .price(SEAT_PRICE)
                .build());
    }

    /**
     * ADR-0013's readiness half: an anonymous caller (the group paths are
     * {@code permitAll}, see ADR-0012) sees {@code UP}, and - proving that
     * answer is not a lie about a broken instance - a real, authenticated
     * hold request against this same context succeeds. If Redis being down
     * secretly broke hold creation, readiness reporting {@code UP} would be
     * exactly the false-negative ADR-0001 exists to prevent, so both halves
     * are asserted together rather than in separate tests that could drift
     * apart.
     */
    @Test
    void readinessStaysUpAndHoldCreationSucceedsWithRedisDown() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/readiness"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("UP"));

        long userId = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow().getId();
        String token = jwtService.generateToken(userId, SEEDED_EMAIL);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new HoldRequest(java.util.List.of(eventSeat.getId())))))
                .andExpect(MockMvcResultMatchers.status().isCreated());
    }

    /**
     * ADR-0013's other half: the Redis indicator is a member of neither
     * public group (ADR-0012), so its true state is an authenticated-only
     * guarantee delivered through the parent aggregate. This asserts that
     * guarantee is actually kept in this outage - {@code redis} reports
     * {@code DOWN}, not silently omitted from {@code components} and not
     * forced to {@code UP} - and, per the packet's "verify, don't assume"
     * instruction, that the parent aggregate's own overall {@code status}
     * genuinely goes {@code DOWN} too, rather than the broken indicator being
     * excluded from the aggregate computation.
     */
    @Test
    void parentAggregateReportsRedisTrulyDownWithRedisDown() throws Exception {
        long userId = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow().getId();
        String token = jwtService.generateToken(userId, SEEDED_EMAIL);

        String body = mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode health = objectMapper.readTree(body);

        assertThat(health.get("status").asText())
                .as("the parent aggregate must honestly report DOWN when Redis is unreachable, per ADR-0013")
                .isEqualTo("DOWN");
        JsonNode components = health.get("components");
        assertThat(components)
                .as("components must be present for an authenticated caller (show-components=always)")
                .isNotNull();
        assertThat(components.has("redis"))
                .as("the redis indicator must not be silently omitted from the parent aggregate")
                .isTrue();
        assertThat(components.get("redis").get("status").asText())
                .as("the redis indicator must report its true state, not be forced to UP")
                .isEqualTo("DOWN");
    }
}
