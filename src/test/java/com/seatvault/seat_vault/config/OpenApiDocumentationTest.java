package com.seatvault.seat_vault.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asserts the shape of the generated {@code /v3/api-docs} document itself
 * (T-005), rather than relying on manual Swagger UI inspection: every
 * controller must carry an {@code @Operation} summary, its real set of
 * {@code @ApiResponse} statuses, and {@code bearerAuth} exactly where
 * ADR-0004 says an operation is user-scoped. As of T-008, {@code
 * SecurityConfig} enforces that boundary for every operation documented here
 * as {@code bearerAuth}, including {@code GET /api/bookings/me} and
 * {@code GET /api/bookings/{id}} - see
 * {@link com.seatvault.seat_vault.controller.BookingController}'s class
 * Javadoc, and {@code BookingIntegrationTest}/{@code AuthIntegrationTest}
 * for the anonymous-request enforcement tests (this class only checks the
 * OpenAPI document, not the live security filter chain).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void apiDocsRendersWithoutError() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }

    @Test
    void bearerAuthSecuritySchemeIsRegistered() throws Exception {
        JsonNode doc = fetchDocument();
        JsonNode schemes = doc.at("/components/securitySchemes/bearerAuth");
        assertThat(schemes.isMissingNode()).isFalse();
        assertThat(schemes.get("type").asText()).isEqualTo("http");
        assertThat(schemes.get("scheme").asText()).isEqualTo("bearer");
    }

    @Test
    void publicCatalogEndpointsHaveSummariesAndRealResponsesButNoSecurityRequirement() throws Exception {
        JsonNode doc = fetchDocument();

        assertOperation(doc, "/api/venues", "get", statuses("200"), false);
        assertOperation(doc, "/api/venues/{id}", "get", statuses("200", "400", "404"), false);
        assertOperation(doc, "/api/events", "get", statuses("200"), false);
        assertOperation(doc, "/api/events/{id}", "get", statuses("200", "400", "404"), false);
        assertOperation(doc, "/api/events/{eventId}/seats", "get", statuses("200", "400", "404"), false);
    }

    @Test
    void authEndpointsDocumentTheirRealResponseSet() throws Exception {
        JsonNode doc = fetchDocument();

        assertOperation(doc, "/api/auth/register", "post", statuses("201", "400", "409"), false);
        assertOperation(doc, "/api/auth/login", "post", statuses("200", "400", "401"), false);
        assertOperation(doc, "/api/auth/me", "get", statuses("200", "401", "404"), true);
    }

    @Test
    void holdEndpointsAreProtectedAndDocumentTheirRealResponseSet() throws Exception {
        JsonNode doc = fetchDocument();

        assertOperation(doc, "/api/holds", "post", statuses("201", "400", "404", "409", "401"), true);
        assertOperation(doc, "/api/holds/{id}", "delete", statuses("204", "400", "404", "409", "401"), true);
    }

    @Test
    void bookingMutationEndpointsAreProtectedAndDocumentTheirRealResponseSet() throws Exception {
        JsonNode doc = fetchDocument();

        assertOperation(doc, "/api/bookings", "post", statuses("201", "400", "404", "409", "401"), true);
        assertOperation(doc, "/api/bookings/{id}/confirm", "post", statuses("200", "400", "404", "401"), true);
        assertOperation(doc, "/api/bookings/{id}/cancel", "post", statuses("200", "400", "404", "409", "401"), true);
    }

    /**
     * The security-relevant assertion this task packet called out explicitly:
     * these two reads are user-scoped (the response depends on who's asking),
     * so ADR-0004 requires {@code bearerAuth} on them, including a documented
     * 401. Since T-008, {@code SecurityConfig} also enforces this at the
     * filter-chain level (see {@code BookingIntegrationTest}'s
     * anonymous-request tests) - this class only checks that the OpenAPI
     * document agrees with that enforcement, not the enforcement itself.
     */
    @Test
    void userScopedBookingReadsDeclareBearerAuthAndDocumentTheirRealResponseSet() throws Exception {
        JsonNode doc = fetchDocument();

        assertOperation(doc, "/api/bookings/me", "get", statuses("200", "404", "401"), true);
        assertOperation(doc, "/api/bookings/{id}", "get", statuses("200", "400", "404", "401"), true);
    }

    /**
     * T-008's stale-resistant guardrail. Rather than pinning individual
     * routes, this walks every operation the generated OpenAPI document
     * actually declares {@code bearerAuth} on - regardless of HTTP verb -
     * and fires each one anonymously against the live {@code
     * SecurityFilterChain}. Every declared {@code @SecurityRequirement(name
     * = "bearerAuth")} in the codebase is a claim that the operation is
     * user-scoped per ADR-0004; this test makes that claim self-enforcing by
     * construction, so a future GET/POST/etc. that declares {@code
     * bearerAuth} (as {@code OpenApiDocumentationTest}'s other tests already
     * require of any user-scoped operation) but which {@code SecurityConfig}
     * does not actually enforce - exactly T-008's bug - fails this test
     * automatically, with no edit to this file required. Since #14 that
     * enforcement gap is much harder to open, the chain now denying by
     * default, but this check is what proves the two agree rather than
     * assuming it.
     * {@code assertThat(operationsChecked)} below guards against the walk
     * silently checking zero operations if the document's shape ever
     * changes.
     */
    @Test
    void everyDeclaredBearerAuthOperationRejectsAnonymousRequestsWith401() throws Exception {
        JsonNode doc = fetchDocument();
        JsonNode paths = doc.get("paths");
        Set<String> httpMethods = Set.of("get", "post", "put", "patch", "delete");

        int operationsChecked = 0;
        for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
            String templatePath = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            for (Map.Entry<String, JsonNode> methodEntry : pathItem.properties()) {
                String httpMethod = methodEntry.getKey();
                if (!httpMethods.contains(httpMethod)) {
                    continue;
                }
                JsonNode operation = methodEntry.getValue();
                boolean requiresBearerAuth = operation.has("security") && !operation.get("security").isEmpty();
                if (!requiresBearerAuth) {
                    continue;
                }

                // Path variables (e.g. {id}) don't need a real value here -
                // Spring Security's authorization check runs before the
                // request ever reaches the controller/repository layer, so
                // any concrete segment is enough to exercise the matcher.
                String concretePath = templatePath.replaceAll("\\{[^}]+}", "1");
                int status = mockMvc.perform(MockMvcRequestBuilders.request(
                                HttpMethod.valueOf(httpMethod.toUpperCase()), concretePath))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                assertThat(status)
                        .as("anonymous %s %s declares bearerAuth in the OpenAPI document, so SecurityConfig "
                                        + "must reject it with 401 rather than %d",
                                httpMethod.toUpperCase(), concretePath, status)
                        .isEqualTo(401);
                operationsChecked++;
            }
        }

        assertThat(operationsChecked)
                .as("this test should have found at least the auth/holds/bookings bearerAuth operations")
                .isGreaterThanOrEqualTo(6);
    }

    /**
     * Closes the residual gap in the guardrail above (#15). That walk can
     * only check operations which already declare {@code bearerAuth}; an
     * endpoint that declares <em>nothing</em> is simply not in its set, so
     * nothing marks it as user-scoped and nothing knows to test it. Since
     * #14 a missing declaration no longer means a missing 401 - the chain
     * denies by default - but it does still mean a route nobody has stated
     * an intent for, and it defeats the one case deny-by-default cannot see:
     * a user-scoped route whose path matches an existing public pattern
     * (a hypothetical {@code GET /api/events/mine} under
     * {@code /api/events/&#123;id&#125;}).
     *
     * <p>So this keys on something an endpoint cannot omit and still work,
     * rather than on its own annotations: a handler that takes
     * {@code @AuthenticationPrincipal} is user-scoped by construction,
     * because it consumes the caller's identity to build its response. It
     * cannot drop that parameter without breaking. Requiring such a handler
     * to declare {@code bearerAuth} then feeds it into the walk above, which
     * proves enforcement. The loop closes: principal-consumption implies
     * declaration, declaration implies enforcement.
     *
     * <p>Done by reflection over Spring's own {@code
     * RequestMappingHandlerMapping} rather than by adding ArchUnit - the
     * property is what matters, not the library, and this needs no new
     * dependency and no second Spring context.
     *
     * <p>The floor below deliberately duplicates the one in {@link
     * #everyDeclaredBearerAuthOperationRejectsAnonymousRequestsWith401()}
     * rather than replacing it. The two enumerate through different
     * machinery and fail differently: that one walks the generated OpenAPI
     * JSON and would go silently empty if springdoc changed its output
     * shape, this one walks the handler mapping and would go silently empty
     * if the controllers stopped resolving. Neither floor would catch the
     * other's enumeration breaking.
     */
    @Test
    void everyPrincipalConsumingHandlerDeclaresBearerAuth() {
        int handlersChecked = 0;

        for (HandlerMethod handlerMethod : handlerMapping.getHandlerMethods().values()) {
            if (!handlerMethod.getBeanType().getPackageName().startsWith("com.seatvault")) {
                continue;
            }
            boolean consumesPrincipal = Arrays.stream(handlerMethod.getMethodParameters())
                    .anyMatch(parameter -> parameter.hasParameterAnnotation(AuthenticationPrincipal.class));
            if (!consumesPrincipal) {
                continue;
            }

            boolean declaresBearerAuth =
                    AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), SecurityRequirement.class)
                            || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(),
                                    SecurityRequirement.class);
            assertThat(declaresBearerAuth)
                    .as("%s#%s takes @AuthenticationPrincipal, so it is user-scoped by construction (ADR-0004) "
                                    + "and must declare @SecurityRequirement(name = \"bearerAuth\")",
                            handlerMethod.getBeanType().getSimpleName(), handlerMethod.getMethod().getName())
                    .isTrue();
            handlersChecked++;
        }

        assertThat(handlersChecked)
                .as("this rule should have found at least the auth/holds/bookings principal-consuming handlers")
                .isGreaterThanOrEqualTo(8);
    }

    private JsonNode fetchDocument() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private void assertOperation(
            JsonNode doc, String path, String httpMethod, String[] expectedStatuses, boolean expectBearerAuth) {
        JsonNode operation = doc.at("/paths/" + path.replace("/", "~1") + "/" + httpMethod);
        assertThat(operation.isMissingNode())
                .as("operation %s %s should be present in the generated document", httpMethod, path)
                .isFalse();

        assertThat(operation.get("summary").asText())
                .as("operation %s %s should have a non-blank @Operation summary", httpMethod, path)
                .isNotBlank();

        JsonNode responses = operation.get("responses");
        for (String status : expectedStatuses) {
            assertThat(responses.has(status))
                    .as("operation %s %s should document response status %s", httpMethod, path, status)
                    .isTrue();
        }
        assertThat(responses.properties())
                .as("operation %s %s should document exactly its real status set %s, not a superset", httpMethod,
                        path, (Object) expectedStatuses)
                .hasSize(expectedStatuses.length);

        boolean hasSecurityRequirement = operation.has("security") && !operation.get("security").isEmpty();
        assertThat(hasSecurityRequirement)
                .as("operation %s %s bearerAuth requirement", httpMethod, path)
                .isEqualTo(expectBearerAuth);
    }

    private static String[] statuses(String... values) {
        return values;
    }
}
