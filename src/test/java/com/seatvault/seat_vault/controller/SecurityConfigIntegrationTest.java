package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import com.seatvault.seat_vault.repository.UserRepository;
import com.seatvault.seat_vault.security.JwtService;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebEndpointProperties webEndpointProperties;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private static final String SEEDED_EMAIL = "alice@example.com";

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

    /**
     * T-002 / ADR-0012: the health GROUP paths, and only the group paths,
     * are on the permitAll allowlist, listed route by route (never
     * {@code /actuator/**}). Each is asserted with its own request so a
     * regression in one group's matcher (e.g. an accidental typo that leaves
     * only one of the two permitted) cannot hide behind the other passing.
     */
    @Test
    void livenessAndReadinessGroupsRespondAnonymouslyAndIndependently() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/liveness"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("UP"));

        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/readiness"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("UP"));
    }

    /**
     * The parent {@code /actuator/health} aggregate is deliberately absent
     * from the permitAll list (ADR-0012/ADR-0013): it reports honestly (DOWN
     * when Redis is unreachable) and that honesty is for an authenticated
     * operator, not an anonymous caller. It therefore falls through to
     * {@code anyRequest().authenticated()} like any other unlisted route.
     */
    @Test
    void parentHealthAggregateRequiresAuthentication() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health"))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("UNAUTHENTICATED")));
    }

    /**
     * {@code /actuator/metrics} is exposed over HTTP (see
     * {@code management.endpoints.web.exposure.include} in
     * application.properties) but carries <b>no {@code requestMatchers}
     * entry at all</b> in {@code SecurityConfig}. The 401 here is not
     * produced by an explicit deny rule; it falls out of
     * {@code anyRequest().authenticated()} the same as any route nobody
     * wrote a decision for. That absence of a matcher IS the mechanism
     * (ADR-0012) - it must not be "fixed" by adding one.
     */
    @Test
    void metricsRequiresAuthenticationBecauseNoMatcherExistsForIt() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/metrics"))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("UNAUTHENTICATED")));
    }

    /**
     * {@code /actuator/env} and {@code /actuator/heapdump} must never be
     * reachable anonymously, but the two are blocked by different mechanisms
     * and this pins which one is actually firing rather than asserting a
     * vague "not 200":
     *
     * <p>Both are absent from {@code management.endpoints.web.exposure.include}
     * (only {@code health,metrics} are listed), so neither is even registered
     * as a Spring MVC handler. But because {@code SecurityConfig}'s chain
     * applies to every request on the shared port (ADR-0012 rejected a
     * separate management port for exactly this reason) and neither path has
     * a permitAll matcher, the security filter chain rejects the anonymous
     * request with 401 <b>before</b> the request ever reaches the
     * dispatcher - the same "no matcher" mechanism that protects
     * {@code /actuator/metrics}. Non-exposure is what stops an
     * <em>authenticated</em> caller from reading them (they would 404 past
     * this point); it is authentication, not exposure, that stops an
     * anonymous one.
     */
    @Test
    void envAndHeapdumpAreUnreachableAnonymouslyBecauseAuthenticationRejectsThemFirst() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/env"))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("UNAUTHENTICATED")));

        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/heapdump"))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(equalTo("UNAUTHENTICATED")));
    }

    /**
     * Supersedes what was, until T-003, a test pinning a T-002-only interim
     * state - recorded here rather than deleted, per T-003's packet, because
     * the earlier state was itself a verified discrepancy against the T-002
     * task packet worth keeping the history of.
     *
     * <p>Before T-003, {@code management.endpoint.health.group.<name>.*} was
     * unconfigured for both "liveness" and "readiness", so Boot substituted a
     * synthetic probe group ({@code AvailabilityProbesHealthEndpointGroup})
     * whose {@code showComponents()}/{@code showDetails()} compile to
     * hardcoded {@code false} - ignoring {@code show-components}/
     * {@code show-details} entirely - and both paths returned exactly
     * {@code {"status":"UP"}}.
     *
     * <p>T-003 (ADR-0013) declares
     * {@code management.endpoint.health.group.readiness.include=readinessState,db}
     * and {@code management.endpoint.health.group.liveness.include=livenessState}.
     * Declaring a group explicitly is what switches Boot from the synthetic
     * probe group to a real, property-driven one that inherits
     * {@code show-components=always}/{@code show-details=when-authorized}
     * from the top level - with no change to {@code SecurityConfig} or this
     * test's request shape. This pins the resulting shape exactly as tightly
     * as the superseded test pinned the old one: each path now carries a
     * {@code components} object naming exactly its configured members, and
     * still no {@code details} for an anonymous caller (that half is
     * unchanged - {@code show-details=when-authorized} was already true
     * before T-003, it just had no group to apply to).
     */
    @Test
    void anonymousLivenessAndReadinessExposePerIndicatorStatusSinceT003ConfiguredTheGroups() throws Exception {
        String livenessBody = mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/liveness"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode liveness = objectMapper.readTree(livenessBody);
        assertThat(liveness.get("status").asText()).isEqualTo("UP");
        assertThat(liveness.get("components").properties())
                .as("liveness must expose exactly its one configured member, livenessState")
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("livenessState");
        assertThat(liveness.get("components").get("livenessState").has("details"))
                .as("an anonymous caller must never see a detail payload (show-details=when-authorized)")
                .isFalse();

        String readinessBody = mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/readiness"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode readiness = objectMapper.readTree(readinessBody);
        assertThat(readiness.get("status").asText()).isEqualTo("UP");
        assertThat(readiness.get("components").properties())
                .as(
                        "readiness must expose exactly its two configured members (readinessState, db) and never"
                                + " redis - ADR-0013's whole point")
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("readinessState", "db");
        assertThat(readiness.get("components").get("db").has("details"))
                .as("an anonymous caller must never see a detail payload (show-details=when-authorized)")
                .isFalse();
    }

    /**
     * ADR-0013's readiness-composition claim, asserted behaviourally against
     * the live group rather than by re-reading the property file: {@code db}
     * is a readiness member and {@code redis} is not. This proves only that
     * one half - which indicators the readiness probe is built from. It does
     * <b>not</b> prove that a failing {@code db} indicator makes readiness
     * report {@code DOWN}; that follow-on is Boot's own status-aggregation
     * behaviour once a {@code DOWN} member exists, not something this
     * codebase implements, and a broken-datasource context is not available
     * to test it directly (Flyway migrates at startup and {@code ddl-auto=
     * validate} runs immediately after, so the context cannot even come up
     * without a working database - see ADR-0013's "Consequences" section).
     * Uses an authenticated caller with {@code show-components=always}/
     * {@code show-details=when-authorized} so {@code components} is
     * populated to inspect at all.
     */
    @Test
    void readinessGroupIncludesDbButNotRedisForAuthenticatedCaller() throws Exception {
        long userId = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow().getId();
        String token = jwtService.generateToken(userId, SEEDED_EMAIL);

        String body = mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/readiness")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode readiness = objectMapper.readTree(body);

        JsonNode components = readiness.get("components");
        assertThat(components).isNotNull();
        assertThat(components.has("db"))
                .as("db must be a readiness member - Postgres is fatal to readiness per ADR-0001/ADR-0013")
                .isTrue();
        assertThat(components.has("redis"))
                .as("redis must NOT be a readiness member - it must never be able to fail readiness per ADR-0001")
                .isFalse();
    }

    /**
     * Proves the other half of ADR-0012's disclosure boundary at the one
     * place it is actually wired today: the parent/primary
     * {@code /actuator/health} aggregate is built by
     * {@code AutoConfiguredHealthEndpointGroups}, which - unlike the
     * liveness/readiness probe groups (see the test above) - inherits
     * {@code management.endpoint.health.show-components}/{@code show-details}
     * directly from the top-level properties when no per-group override
     * exists. Since the parent aggregate requires authentication
     * (deliberately, per ADR-0012/0013), this uses a real JWT for a seeded
     * user rather than an anonymous request; ADR-0012's "Consequences"
     * section notes this system has no roles, so any authenticated caller
     * satisfies {@code show-details=when-authorized}.
     */
    @Test
    void showComponentsAndShowDetailsAlreadyGovernTheAuthenticatedParentAggregate() throws Exception {
        long userId = userRepository.findByEmailIgnoreCase(SEEDED_EMAIL).orElseThrow().getId();
        String token = jwtService.generateToken(userId, SEEDED_EMAIL);

        String body = mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode health = objectMapper.readTree(body);

        JsonNode components = health.get("components");
        assertThat(components)
                .as("show-components=always should list each indicator contributing to the parent aggregate")
                .isNotNull();
        assertThat(components.isEmpty())
                .as("the parent aggregate should have at least one contributing indicator")
                .isFalse();
        boolean anyComponentHasDetails = false;
        for (JsonNode component : components) {
            if (component.has("details")) {
                anyComponentHasDetails = true;
            }
        }
        assertThat(anyComponentHasDetails)
                .as("show-details=when-authorized should reveal detail payloads to an authenticated caller")
                .isTrue();
    }

    /**
     * Guards the exposure lever (ADR-0012: "an explicit allowlist, never
     * {@code *}"). This reads the same {@code WebEndpointProperties} bean
     * Boot itself binds {@code management.endpoints.web.exposure.include}
     * into and asserts its resolved value, not the property file text, so it
     * observes exactly what the running application would actually expose.
     * Proved by breaking it: temporarily setting the property to {@code *}
     * makes this assertion fail (see the builder's report for the observed
     * failure), which is what LESSONS requires of a guard before it counts
     * as coverage.
     */
    @Test
    void actuatorExposureIsTheExplicitAllowlistNotAWildcard() {
        assertThat(webEndpointProperties.getExposure().getInclude())
                .as("management.endpoints.web.exposure.include must be the explicit allowlist from ADR-0012")
                .isEqualTo(Set.of("health", "metrics"));
    }
}
