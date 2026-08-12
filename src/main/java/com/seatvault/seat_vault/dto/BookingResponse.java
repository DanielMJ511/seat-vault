package com.seatvault.seat_vault.dto;

import com.seatvault.seat_vault.entity.BookingStatus;
import java.time.Instant;
import java.util.List;

public record BookingResponse(
        Long id, BookingStatus status, Instant createdAt, List<BookingSeatResponse> seats, PaymentResponse payment) {
}
