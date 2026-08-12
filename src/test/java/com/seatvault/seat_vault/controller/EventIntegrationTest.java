package com.seatvault.seat_vault.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.entity.Event;
import com.seatvault.seat_vault.repository.EventRepository;
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

/**
 * End-to-end coverage of the {@code /api/events/**} read surface against a
 * real Postgres stack, including the seeded events from {@code db/seed}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class EventIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @Test
    void listAllReturnsSeededEventsOrderedByStartsAt() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/events"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$[0].name").value(equalTo("Opening Night Gala")))
                .andExpect(MockMvcResultMatchers.jsonPath("$[0].venueId").value(notNullValue()))
                .andExpect(MockMvcResultMatchers.jsonPath("$[0].venueName").value(equalTo("The Grand Hall")))
                .andExpect(MockMvcResultMatchers.jsonPath("$[1].name").value(equalTo("Riverside Jazz Night")))
                .andExpect(MockMvcResultMatchers.jsonPath("$[1].venueId").value(notNullValue()))
                .andExpect(MockMvcResultMatchers.jsonPath("$[1].venueName").value(equalTo("Riverside Theater")));
    }

    @Test
    void getByIdReturnsMatchingSeedRow() throws Exception {
        Event gala = eventRepository.findAllWithVenueOrderByStartsAtAsc().stream()
                .filter(e -> e.getName().equals("Opening Night Gala"))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/events/{id}", gala.getId()))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(gala.getId()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.name").value(equalTo("Opening Night Gala")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.venueName").value(equalTo("The Grand Hall")));
    }

    @Test
    void getByIdWithUnknownIdIsNotFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/events/999999"))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("EVENT_NOT_FOUND")));
    }
}
