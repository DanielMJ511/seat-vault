package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.EventSeat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

    @Query("""
            select es from EventSeat es
            join fetch es.seat s
            left join fetch es.currentHold
            where es.event.id = :eventId
            order by s.section asc, s.rowLabel asc, s.seatNumber asc
            """)
    List<EventSeat> findByEventIdWithSeatAndHold(@Param("eventId") Long eventId);
}
