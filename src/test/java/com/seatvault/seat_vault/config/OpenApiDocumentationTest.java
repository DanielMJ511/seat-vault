package com.seatvault.seat_vault.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asserts the shape of the generated {@code /v3/api-docs} document itself
 * (T-005), rather than relying on manual Swagger UI inspection: every
 * controller must carry an {@code @Operation} summary, its real set of
 * {@code @ApiResponse} statuses, and {@code bearerAuth} exactly where
 * ADR-0004 says an operation is user-scoped - regardless of whether {@code
 * SecurityConfig} actually enforces that today (it doesn't, for {@code
 * GET /api/bookings/me} and {@code GET /api/bookings/{id}} - see the
 * class-level Javadoc on {@link com.seatvault.seat_vault.controller.BookingController}).
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
     * so ADR-0004 requires {@code bearerAuth} on them even though {@code
     * SecurityConfig}'s blanket {@code GET /api/**} permitAll rule doesn't
     * enforce it today. Documenting the intended contract here is deliberate
     * - see {@link com.seatvault.seat_vault.controller.BookingController}'s
     * class Javadoc for the full explanation of the gap, which is out of
     * scope to fix in this task.
     */
    @Test
    void userScopedBookingReadsDeclareBearerAuthDespiteBeingUserScopedGets() throws Exception {
        JsonNode doc = fetchDocument();

        assertOperation(doc, "/api/bookings/me", "get", statuses("200", "404"), true);
        assertOperation(doc, "/api/bookings/{id}", "get", statuses("200", "400", "404"), true);
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
