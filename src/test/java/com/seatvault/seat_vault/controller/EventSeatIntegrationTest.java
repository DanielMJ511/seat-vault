package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.equalTo;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.entity.Event;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import com.seatvault.seat_vault.entity.Hold;
import com.seatvault.seat_vault.entity.HoldStatus;
import com.seatvault.seat_vault.entity.User;
import com.seatvault.seat_vault.repository.EventRepository;
import com.seatvault.seat_vault.repository.EventSeatRepository;
import com.seatvault.seat_vault.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end coverage of the {@code /api/events/{id}/seats} read surface
 * against a real Postgres stack, including the seeded seat grid from
 * {@code db/seed} and the lazy-expiry rule ({@link
 * com.seatvault.seat_vault.service.EventSeatAvailability}) applied at read
 * time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class EventSeatIntegrationTest {

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
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void listForEventReturnsSeededSeatsOrderedWithCorrectPricing() throws Exception {
        Event gala = galaEvent();

        String body = mockMvc.perform(MockMvcRequestBuilders.get("/api/events/{id}/seats", gala.getId()))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.length()").value(30))
                .andExpect(MockMvcResultMatchers.jsonPath("$[*].status").value(everyItem(equalTo("AVAILABLE"))))
                .andExpect(MockMvcResultMatchers.jsonPath("$[0].section").value(equalTo("A")))
                .andExpect(MockMvcResultMatchers.jsonPath("$[0].rowLabel").value(equalTo("A")))
                .andExpect(MockMvcResultMatchers.jsonPath("$[0].seatNumber").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode seats = objectMapper.readTree(body);
        long sectionACount = 0;
        long sectionBCount = 0;
        for (JsonNode seat : seats) {
            BigDecimal price = new BigDecimal(seat.get("price").asText());
            if (seat.get("section").asText().equals("A")) {
                assertThat(price).isEqualByComparingTo("150.00");
                sectionACount++;
            } else {
                assertThat(price).isEqualByComparingTo("95.00");
                sectionBCount++;
            }
        }
        assertThat(sectionACount).isEqualTo(15);
        assertThat(sectionBCount).isEqualTo(15);
    }

    @Test
    void listForEventWithUnknownEventIdIsNotFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/events/999999/seats"))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("EVENT_NOT_FOUND")));
    }

    /**
     * Runs the whole scenario — the raw {@code Hold} persist, the seat
     * mutation, and the MockMvc-driven read — inside one test-managed
     * transaction that Spring rolls back automatically once the method
     * returns. MockMvc dispatches in-process on this same thread, so the
     * controller's read joins this same transaction/connection and sees the
     * uncommitted writes. This means no manual cleanup is needed and a
     * failure partway through can never leave seeded data mutated for other
     * tests, unlike a manual save-then-restore-in-finally approach.
     */
    @Test
    @Transactional
    void heldSeatWithExpiredHoldIsReportedAsAvailableWithoutMutatingStoredStatus() throws Exception {
        Event gala = galaEvent();
        User seededUser = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow();

        List<EventSeat> galaSeats = eventSeatRepository.findByEventIdWithSeatAndHold(gala.getId());
        EventSeat targetSeat = galaSeats.get(0);

        Hold expiredHold = Hold.builder()
                .user(seededUser)
                .status(HoldStatus.ACTIVE)
                .expiresAt(Instant.now().minusSeconds(60))
                .build();
        // Raw EntityManager#persist relies on the transaction this test
        // method already runs inside (there's deliberately no HoldRepository
        // yet, so this is the one place that needs direct EntityManager use).
        entityManager.persist(expiredHold);
        entityManager.flush();

        targetSeat.setStatus(EventSeatStatus.HELD);
        targetSeat.setCurrentHold(expiredHold);
        eventSeatRepository.saveAndFlush(targetSeat);

        String body = mockMvc.perform(MockMvcRequestBuilders.get("/api/events/{id}/seats", gala.getId()))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode seats = objectMapper.readTree(body);
        JsonNode targetSeatJson = null;
        for (JsonNode seat : seats) {
            if (seat.get("id").asLong() == targetSeat.getId()) {
                targetSeatJson = seat;
                break;
            }
        }
        assertThat(targetSeatJson).isNotNull();
        assertThat(targetSeatJson.get("status").asText()).isEqualTo("AVAILABLE");

        EventSeat reloaded = eventSeatRepository.findById(targetSeat.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EventSeatStatus.HELD);
    }

    private Event galaEvent() {
        return eventRepository.findAllWithVenueOrderByStartsAtAsc().stream()
                .filter(e -> e.getName().equals(GALA_EVENT_NAME))
                .findFirst()
                .orElseThrow();
    }
}
