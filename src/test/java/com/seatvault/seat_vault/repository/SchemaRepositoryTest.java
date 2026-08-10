package com.seatvault.seat_vault.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatvault.seat_vault.config.PostgresTestcontainersConfig;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfig.class)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class SchemaRepositoryTest {

    private final VenueRepository venueRepository;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final EventSeatRepository eventSeatRepository;

    @Test
    void seededVenuesEventsAndSeatsAreQueryable() {
        // V8__seed_reference_data.sql: 2 venues x (2 sections x 3 rows x 5 seats) = 60 seats,
        // 1 event per venue, 30 event_seats per event = 60 event_seats.
        assertThat(venueRepository.count()).isEqualTo(2);
        assertThat(eventRepository.count()).isEqualTo(2);
        assertThat(seatRepository.count()).isEqualTo(60);
        assertThat(eventSeatRepository.count()).isEqualTo(60);
    }

    @Test
    void duplicateEventSeatForSameEventAndSeatIsRejected() {
        EventSeat existing = eventSeatRepository.findAll().get(0);

        EventSeat duplicate = EventSeat.builder()
                .event(existing.getEvent())
                .seat(existing.getSeat())
                .status(EventSeatStatus.AVAILABLE)
                .price(new BigDecimal("10.00"))
                .build();

        assertThatThrownBy(() -> eventSeatRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
