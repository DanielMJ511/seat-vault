package com.seatvault.seat_vault.exception;

import static org.hamcrest.Matchers.equalTo;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.repository.UserRepository;
import com.seatvault.seat_vault.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Covers the translations {@code GlobalExceptionHandler} performs for
 * failures that originate in the request pipeline itself rather than in a
 * service - the cases with no {@code ApiException} behind them.
 *
 * <p>The routing-miss tests are #17's regression coverage. Before that fix
 * an unmapped path produced {@code NoResourceFoundException}, which nothing
 * handled explicitly, so it fell through to
 * {@code @ExceptionHandler(Exception.class)} and was reported as a 500 -
 * logged at {@code ERROR} with a stack trace, as though the server had
 * broken. A mistyped URL is neither a server fault nor worth retrying, and
 * treating routine 404s as incidents is how {@code ERROR} stops meaning
 * anything.
 *
 * <p>These requests are authenticated deliberately. Since #14 the filter
 * chain denies by default, so an anonymous request to an unmapped path is
 * rejected with 401 before the dispatcher runs at all - which masks this
 * defect rather than fixing it. Passing a valid token is what gets the
 * request far enough to exercise the handler.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class GlobalExceptionHandlerIntegrationTest {

    private static final String SEEDED_EMAIL = "alice@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void unmappedPathUnderApiReturnsRouteNotFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/not-a-real-route")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor()))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(404))
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("ROUTE_NOT_FOUND")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.path").value(equalTo("/api/not-a-real-route")));
    }

    /**
     * The defect was never limited to {@code /api}: {@code
     * NoResourceFoundException} is thrown for any path with no handler, so a
     * fix scoped to the API prefix would leave the same 500 everywhere else.
     */
    @Test
    void unmappedPathOutsideApiReturnsRouteNotFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/totally-bogus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor()))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("ROUTE_NOT_FOUND")));
    }

    /**
     * The distinction the new code exists to draw (ADR-0009: split only when
     * the client's next action differs). Here the route resolves and the
     * <em>value</em> is unusable, so the caller should fix the id - not the
     * URL. This already worked; it is pinned so the #17 fix cannot widen
     * into it and collapse the two answers back together.
     */
    @Test
    void mappedRouteWithUnparseableIdStillReturnsInvalidParameter() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/bookings/not-a-number")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor()))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("INVALID_PARAMETER")));
    }

    /**
     * The other side of the same boundary: a resolved route whose entity is
     * genuinely absent keeps its domain-specific 404, rather than being
     * flattened into the routing code.
     */
    @Test
    void mappedRouteWithMissingEntityKeepsItsDomainNotFoundCode() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/venues/{id}", 999_999)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor()))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("VENUE_NOT_FOUND")));
    }

    private String tokenFor() {
        long userId = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow().getId();
        return jwtService.generateToken(userId, SEEDED_EMAIL);
    }
}
