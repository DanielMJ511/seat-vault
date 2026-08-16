package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Pins {@code SecurityConfig}'s <b>posture</b> rather than any individual
 * route: the chain permits an enumerated set of public routes and denies
 * everything else, so a route added without an auth decision fails closed.
 *
 * <p>This is the structural fix for the defect class behind T-008 (#12) and
 * is what issue #14 asked for. Until then the chain ended with a blanket
 * {@code GET /api/**} permitAll, which made the default <em>allow</em> and
 * left safety depending on remembering to carve out every user-scoped
 * exception ahead of it. {@code GET /api/bookings/me} and
 * {@code GET /api/bookings/&#123;id&#125;} shipped without that carve-out and
 * were served anonymously - the very outcome {@code SecurityConfig}'s own
 * Javadoc had warned about by name and did not prevent.
 *
 * <p>{@link #unmappedApiPathIsDeniedRatherThanServed()} is the test that
 * distinguishes the two designs. Under the old chain that path matched the
 * blanket permitAll and got as far as handler resolution; under the new one
 * it never leaves the filter chain. Written against the old config it fails,
 * which is the only reason it is worth having.
 *
 * <p>Note what this posture still does not catch, and why that is acceptable:
 * a user-scoped route whose path matches an <em>existing</em> public pattern
 * (a hypothetical {@code GET /api/events/mine} sliding under
 * {@code /api/events/&#123;id&#125;}) would still be permitted here. That
 * residual is covered from the other side by
 * {@code OpenApiDocumentationTest#everyPrincipalConsumingHandlerDeclaresBearerAuth}
 * (#15), since such a handler must take {@code @AuthenticationPrincipal},
 * must therefore declare {@code bearerAuth}, and is therefore fired
 * anonymously by that class's runtime walk. See ADR-0004.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * The deny-by-default assertion. No handler is mapped at this path, so
     * what is being pinned is which layer rejects it: the authorization
     * chain (401), not the dispatcher (404). A 404 here would mean the
     * request was authorized first and only then found to be unmapped -
     * i.e. that the chain still defaults to allow.
     */
    @Test
    void unmappedApiPathIsDeniedRatherThanServed() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/not-a-real-route"))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(401))
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("UNAUTHENTICATED")));
    }

    /**
     * The other half of the contract: inverting the default must not
     * accidentally close a route that is genuinely public. These five reads
     * return the same answer to everyone (ADR-0004), so each must remain
     * reachable with no credentials.
     *
     * <p>Asserts only "not 401" rather than a specific status, keeping this
     * test about authorization - the per-route response bodies are already
     * covered by {@code VenueIntegrationTest}, {@code EventIntegrationTest},
     * and {@code EventSeatIntegrationTest}.
     */
    @Test
    void publicCatalogReadsRemainReachableAnonymously() throws Exception {
        String[] publicPaths = {
            "/api/venues", "/api/venues/1", "/api/events", "/api/events/1", "/api/events/1/seats",
        };

        for (String path : publicPaths) {
            int status = mockMvc.perform(MockMvcRequestBuilders.get(path))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status)
                    .as("%s is a public catalog read (ADR-0004) and must not require authentication", path)
                    .isNotEqualTo(401);
        }
    }

    /**
     * The OpenAPI document and Swagger UI are served outside {@code /api/**}
     * and so depend on their own permitAll entries surviving the inversion.
     * {@code /v3/api-docs} is matched by the {@code /v3/api-docs/**} pattern
     * because a trailing {@code /**} also matches zero path segments.
     */
    @Test
    void apiDocumentationRemainsReachableAnonymously() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }
}
