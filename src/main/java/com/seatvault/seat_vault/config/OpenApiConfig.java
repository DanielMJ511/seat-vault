package com.seatvault.seat_vault.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI document metadata, and registration of the bearer-token
 * security scheme so Swagger UI shows an Authorize button for JWT-protected
 * endpoints (referenced via {@code @SecurityRequirement(name = "bearerAuth")}
 * on the operations in {@code controller/} that need it - see ADR-0004 for
 * which those are).
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "SeatVault API",
                version = "v1",
                description = "Event/venue seat-reservation API: browse venues, events, and seats; place a "
                        + "temporary Hold on seats during checkout; convert a Hold into a Booking and confirm "
                        + "its Payment. See CONTEXT.md and docs/adr/ for the full domain model and concurrency "
                        + "design."))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER)
public class OpenApiConfig {
}
