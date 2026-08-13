package com.seatvault.seat_vault.repository;

import com.seatvault.seat_vault.entity.Booking;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * The concurrency-correctness mechanism for {@code confirmPayment}, mirroring
     * {@link EventSeatRepository#findByIdForUpdate(Long)}: a Postgres
     * {@code SELECT ... FOR UPDATE} row lock, held for the duration of the
     * caller's transaction, serializing every writer that could touch this
     * Booking or its linked Payment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    Optional<Booking> findByHoldId(Long holdId);

    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
}
