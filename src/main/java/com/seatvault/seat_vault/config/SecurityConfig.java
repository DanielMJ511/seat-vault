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
 * Stateless JWT-based security configuration, <b>deny by default</b>.
 * <p>
 * The governing principle (see {@code docs/adr/0004-auth-boundary-is-response-identity-dependence-not-http-verb.md})
 * is that a route is public if and only if its response doesn't depend on
 * who's asking — not simply "GET is public."
 * <p>
 * The chain below enumerates the public routes and ends at
 * {@code anyRequest().authenticated()}. Nothing else is listed, because
 * nothing else needs to be: a route added without an auth decision is
 * authenticated automatically. That inversion is the point. Until #14 this
 * chain ended with a blanket {@code GET /api/**} permitAll, which made the
 * default <em>allow</em> and left safety depending on remembering to
 * register an {@code authenticated()} carve-out for each user-scoped GET
 * ahead of it. "Forgot to carve out an exception" fails open and is
 * invisible; "forgot to permit a public route" fails closed with a 401 and
 * is obvious the first time anyone calls it.
 * <p>
 * That was not a theoretical concern. {@code GET /api/bookings/me} and
 * {@code GET /api/bookings/{id}} shipped in M6 without a carve-out and were
 * served to anonymous callers until T-008 (#12) — and the Javadoc this text
 * replaces had explicitly warned that any future user-scoped GET would need
 * one, naming "my bookings" as the example. A comment in the right file
 * naming the right route did not prevent the bug, which is the whole
 * argument for a structural default over a documented convention.
 * <p>
 * One residual remains, deliberately: a user-scoped route whose path matches
 * an existing public pattern (say {@code GET /api/events/mine} sliding under
 * {@code /api/events/{id}}) would still be permitted here. It is caught from
 * the annotation side instead, by
 * {@code OpenApiDocumentationTest#everyPrincipalConsumingHandlerDeclaresBearerAuth}
 * (#15). See {@code SecurityConfigIntegrationTest} for the posture tests.
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
                        // Public: the two ways to obtain a token. Everything
                        // else under /api/auth is user-scoped.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // Public: catalog/browse reads. Each returns the same
                        // answer to every caller, including none, which is
                        // exactly ADR-0004's test for publicness. Listed
                        // route by route rather than by prefix so that adding
                        // a route under one of these paths is not silently
                        // public too.
                        .requestMatchers(HttpMethod.GET,
                                "/api/venues", "/api/venues/{id}",
                                "/api/events", "/api/events/{id}",
                                "/api/events/{eventId}/seats").permitAll()
                        // Public: the API documentation. A trailing /** also
                        // matches zero segments, so /v3/api-docs itself is
                        // covered by the first pattern.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Public: the health GROUP paths only (see ADR-0012).
                        // Liveness/readiness return the same UP/DOWN answer
                        // to every caller including none, which is exactly
                        // ADR-0004's test for publicness. Listed route by
                        // route, never as a /actuator/** prefix, because that
                        // prefix would also publish /actuator/env and
                        // /actuator/heapdump the moment either was exposed.
                        // The parent /actuator/health is deliberately absent
                        // from this list: per ADR-0013 it reports honestly
                        // (DOWN when Redis is down), and that honesty is for
                        // operators, not anonymous callers, so it falls
                        // through to anyRequest().authenticated() below.
                        // /actuator/metrics is exposed (see
                        // application.properties) but ALSO has no matcher
                        // here — that absence is not an oversight, it is the
                        // mechanism: deny-by-default authenticates it for
                        // free. Do not "fix" this by adding a matcher.
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        // Everything else — including any route added without
                        // an auth decision, and any path with no handler at
                        // all — requires authentication.
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
