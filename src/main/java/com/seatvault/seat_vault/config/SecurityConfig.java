package com.seatvault.seat_vault.config;

import com.seatvault.seat_vault.dto.ErrorResponse;
import com.seatvault.seat_vault.exception.ErrorCode;
import com.seatvault.seat_vault.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateless JWT-based security configuration.
 * <p>
 * The governing principle (see {@code docs/adr/0004-auth-boundary-is-response-identity-dependence-not-http-verb.md})
 * is that a route is public if and only if its response doesn't depend on
 * who's asking — not simply "GET is public." {@code /api/auth/register} and
 * {@code /api/auth/login} are always public, and most {@code GET /api/**}
 * requests are public too, but that blanket GET rule is a placeholder
 * standing in for catalog/browse endpoints M3 hasn't introduced yet, not a
 * claim that every future GET should be public. {@code GET /api/auth/me},
 * {@code GET /api/bookings/me}, and {@code GET /api/bookings/{id}} are the user-scoped
 * exceptions today: their matchers are registered ahead of both permitAll
 * rules so they always require authentication. Any future user-scoped GET
 * must get the same explicit carve-out, registered before the blanket GET
 * rule — matcher order is first-match-wins. (T-008: the booking pair above
 * was originally missed; see {@code OpenApiDocumentationTest} for a
 * doc/enforcement-agreement check and {@code BookingIntegrationTest} for the
 * anonymous-request regression test.)
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/bookings/me", "/api/bookings/{id}")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Security-layer authentication failures never reach
     * {@code GlobalExceptionHandler} (they're handled before the DispatcherServlet's
     * exception-resolving machinery gets involved), so this writes the same
     * {@link ErrorResponse} JSON shape directly.
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            ErrorResponse body = new ErrorResponse(
                    Instant.now(),
                    ErrorCode.UNAUTHENTICATED.getStatus().value(),
                    ErrorCode.UNAUTHENTICATED.name(),
                    ErrorCode.UNAUTHENTICATED.getDescription(),
                    request.getRequestURI());

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(body));
        };
    }

    /**
     * Mirrors {@link #authenticationEntryPoint()}: authorization failures
     * (authenticated, but lacking the required role/authority) never reach
     * {@code GlobalExceptionHandler} either, so this writes the same
     * {@link ErrorResponse} JSON shape directly, with a 403 status. No
     * authorization rule uses {@code hasRole}/{@code hasAuthority} yet, so
     * this isn't exercised today, but it's wired up ahead of M3+ needing it.
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            ErrorResponse body = new ErrorResponse(
                    Instant.now(),
                    ErrorCode.ACCESS_DENIED.getStatus().value(),
                    ErrorCode.ACCESS_DENIED.name(),
                    ErrorCode.ACCESS_DENIED.getDescription(),
                    request.getRequestURI());

            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(body));
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
