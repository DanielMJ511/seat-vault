package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.Event;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query("select e from Event e join fetch e.venue order by e.startsAt asc")
    List<Event> findAllWithVenueOrderByStartsAtAsc();

    @Query("select e from Event e join fetch e.venue where e.id = :id")
    Optional<Event> findByIdWithVenue(@Param("id") Long id);
}
