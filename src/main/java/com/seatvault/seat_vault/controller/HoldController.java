package com.seatvault.seat_vault.controller;

import com.seatvault.seat_vault.dto.HoldRequest;
import com.seatvault.seat_vault.dto.HoldResponse;
import com.seatvault.seat_vault.security.AuthenticatedUser;
import com.seatvault.seat_vault.service.HoldService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/holds")
@RequiredArgsConstructor
public class HoldController {

    private final HoldService holdService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody HoldRequest request) {
        return holdService.createHold(principal.id(), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        holdService.releaseHold(principal.id(), id);
    }
}
