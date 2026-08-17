package com.seatvault.seat_vault.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatvault.seat_vault.config.DeadPortDbHealthProbeTargetTestConfig;
import com.seatvault.seat_vault.config.PostgresTestcontainersConfig;
import java.time.Duration;
import java.time.Instant;
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
 * Makes ADR-0014's central claim falsifiable end to end: with the {@code db}
 * readiness indicator's own connection path pointed at a dead port (via
 * {@link DeadPortDbHealthProbeTargetTestConfig}) while the application's real
 * datasource stays on a genuine Testcontainers Postgres (via {@link
 * PostgresTestcontainersConfig}) - so the context boots, Flyway migrates, and
 * {@code ddl-auto=validate} passes normally - {@code
 * /actuator/health/readiness} reports a prompt {@code DOWN}/503 instead of
 * hanging.
 *
 * <p>This is the direct application of M8's retro lesson ("untestable"
 * usually means "untestable at the layer being thought about") to the wall
 * ADR-0013 hit: that ADR could only assert readiness *group composition*,
 * because the context could not boot with Postgres broken while the health
 * check shared the application datasource. Decoupling the connection path is
 * what makes this test possible at all.
 *
 * <p><b>What this test does and does not prove - read before trusting its
 * green.</b> A dead port produces a <em>refused</em> connection, which fails
 * near-instantly regardless of {@code connectTimeout}/{@code
 * socketTimeout}/{@code loginTimeout}. So this proves the fail-fast
 * <em>mechanism</em> and the decoupling (the context boots on a real database
 * while readiness reports {@code DOWN}); it proves <b>nothing</b> about those
 * timeout values, which only bite on a host that hangs rather than refuses -
 * {@link #MAX_ELAPSED} is a loose bound proving absence of a hang, not a
 * measurement of the worst case ADR-0014 budgets for. Those values have since
 * been measured out-of-band against a {@code docker pause}d server - ~5s,
 * composed of a 3.0s login bound and a separate 2.0s query bound; see
 * ADR-0014's Consequences - which is a separate exercise from this test and
 * does not change what this test covers. Nor does it prove the health path and the
 * application datasource point at the same database - that is a wiring
 * property, observable only at the container layer (see M8/T-004's
 * {@code docker stop}-based verification, re-run for this change against the
 * widened {@code HEALTHCHECK --timeout=6s}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, DeadPortDbHealthProbeTargetTestConfig.class})
@ActiveProfiles("test")
class HealthReadinessPostgresDownIntegrationTest {

    /**
     * Loose upper bound proving no hang - comfortably below the original
     * unbounded-until-Hikari's-30s-timeout stall this task exists to fix,
     * with generous headroom for a slow CI box. A refused connection on a
     * dead port fails in milliseconds, so this bound is not a measurement of
     * the {@code connectTimeout=2}/{@code socketTimeout=2}/{@code
     * loginTimeout=3} values - see the class Javadoc. Do not read it as
     * headroom over those values either: it happens to equal the probe's ~5s
     * composed worst case, which is a coincidence of two unrelated choices,
     * not an assertion about it.
     */
    private static final Duration MAX_ELAPSED = Duration.ofSeconds(5);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void readinessReturnsPromptDownWithPostgresUnreachable() throws Exception {
        Instant start = Instant.now();

        String body = mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/readiness"))
                .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Duration elapsed = Duration.between(start, Instant.now());
        assertThat(elapsed)
                .as("readiness must fail fast, not hang - see class Javadoc for what this bound does "
                        + "and does not prove")
                .isLessThan(MAX_ELAPSED);

        JsonNode health = objectMapper.readTree(body);
        assertThat(health.get("status").asText())
                .as("readiness must honestly report DOWN when Postgres is unreachable, per ADR-0013")
                .isEqualTo("DOWN");

        // Confirmed by observation, not by reading configuration: M8/T-003
        // found Boot silently substitutes a synthetic probe group whose
        // showComponents()/showDetails() compile to hardcoded false unless
        // the group is explicitly declared under
        // management.endpoint.health.group.<name>.* - which it is here, but
        // this assertion is what actually proves the custom dbHealthIndicator
        // landed in the readiness group as "db", rather than trusting the
        // property.
        JsonNode components = health.get("components");
        assertThat(components)
                .as("show-components=always applies to anonymous readiness callers too (ADR-0012)")
                .isNotNull();
        assertThat(components.has("db"))
                .as("the custom dbHealthIndicator must resolve as 'db' in the readiness group")
                .isTrue();
        assertThat(components.get("db").get("status").asText())
                .isEqualTo("DOWN");
    }
}
