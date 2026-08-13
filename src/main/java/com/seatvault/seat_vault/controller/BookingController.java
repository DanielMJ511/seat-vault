package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.BookingResponse;
import com.seatvault.seat_vault.dto.CreateBookingRequest;
import com.seatvault.seat_vault.security.AuthenticatedUser;
import com.seatvault.seat_vault.service.BookingService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody CreateBookingRequest request) {
        return bookingService.createFromHold(principal.id(), request);
    }

    @PostMapping("/{id}/confirm")
    public BookingResponse confirm(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return bookingService.confirmPayment(principal.id(), id);
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return bookingService.cancel(principal.id(), id);
    }

    // Literal "/me" is matched ahead of the "/{id}" template by Spring MVC's
    // path specificity rules regardless of declaration order, so this is safe
    // even though it's declared after "/{id}/confirm" above.
    @GetMapping("/me")
    public List<BookingResponse> listMine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bookingService.listBookings(principal.id());
    }

    @GetMapping("/{id}")
    public BookingResponse getById(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return bookingService.getBooking(principal.id(), id);
    }
}
