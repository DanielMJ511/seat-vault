package com.seatvault.seat_vault.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.entity.EventSeat;
import com.seatvault.seat_vault.entity.EventSeatStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfig.class)
class SchemaRepositoryTest {

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private EventSeatRepository eventSeatRepository;

    @Test
    void seededVenuesEventsAndSeatsAreQueryable() {
        assertThat(venueRepository.count()).isGreaterThan(0);
        assertThat(eventRepository.count()).isGreaterThan(0);
        assertThat(seatRepository.count()).isGreaterThan(0);
        assertThat(eventSeatRepository.count()).isGreaterThan(0);
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
