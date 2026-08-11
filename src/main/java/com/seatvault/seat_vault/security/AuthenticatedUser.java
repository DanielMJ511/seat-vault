package com.seatvault.seat_vault.security;

/**
 * JWT principal set on the {@link org.springframework.security.core.context.SecurityContext}
 * by {@link JwtAuthenticationFilter}. Trusts the signed token claims directly
 * (no per-request database lookup), consistent with the stateless design.
 */
public record AuthenticatedUser(Long id, String email) {
}
