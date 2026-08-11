package com.seatvault.seat_vault.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-must-be-at-least-32-bytes-long";

    private JwtService newJwtService(long expirationMinutes) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpirationMinutes(expirationMinutes);
        return new JwtService(properties);
    }

    @Test
    void generateThenParseRoundTripsUserIdAndEmail() {
        JwtService jwtService = newJwtService(60);

        String token = jwtService.generateToken(42L, "alice@example.com");

        assertThat(jwtService.extractUserId(token)).isEqualTo(42L);
        assertThat(jwtService.extractEmail(token)).isEqualTo("alice@example.com");
    }

    @Test
    void tamperedSignatureIsRejected() {
        JwtService jwtService = newJwtService(60);
        String token = jwtService.generateToken(1L, "alice@example.com");

        // Flip the last character of the signature segment.
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> jwtService.extractUserId(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        JwtService jwtService = newJwtService(0);
        String token = jwtService.generateToken(1L, "alice@example.com");

        // exp == iat when expirationMinutes is 0; wait a moment to guarantee "now" has passed it.
        Thread.sleep(1_100);

        assertThatThrownBy(() -> jwtService.extractUserId(token))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
