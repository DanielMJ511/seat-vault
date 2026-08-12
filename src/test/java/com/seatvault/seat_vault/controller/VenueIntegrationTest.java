package com.seatvault.seat_vault.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.entity.Venue;
import com.seatvault.seat_vault.repository.VenueRepository;
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
 * End-to-end coverage of the {@code /api/venues/**} read surface against a
 * real Postgres stack, including the seeded venues from {@code db/seed}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class VenueIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VenueRepository venueRepository;

    @Test
    void listAllReturnsSeededVenues() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/venues"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$[*].name")
                        .value(hasItem("The Grand Hall")))
                .andExpect(MockMvcResultMatchers.jsonPath("$[*].name")
                        .value(hasItem("Riverside Theater")));
    }

    @Test
    void getByIdReturnsMatchingSeedRow() throws Exception {
        Venue grandHall = venueRepository.findAllByOrderByNameAsc().stream()
                .filter(v -> v.getName().equals("The Grand Hall"))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/venues/{id}", grandHall.getId()))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(grandHall.getId()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.name").value(equalTo("The Grand Hall")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.address")
                        .value(equalTo("123 Main St, Springfield")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.createdAt").value(notNullValue()));
    }

    @Test
    void getByIdWithUnknownIdIsNotFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/venues/999999"))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("VENUE_NOT_FOUND")));
    }
}
