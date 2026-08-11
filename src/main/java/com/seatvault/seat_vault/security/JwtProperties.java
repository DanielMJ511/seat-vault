package com.seatvault.seat_vault.security;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Binds {@code security.jwt.*} configuration and fails startup fast if the
 * signing secret is unsafe, instead of letting a weak or default secret
 * silently run in a real deployment.
 * <p>
 * Deliberately has no other constructor dependencies: a
 * {@code @ConfigurationProperties} class with exactly one parameterized
 * constructor is implicitly treated by Spring Boot as a value object bound
 * via constructor binding, which would silently stop {@code secret}/
 * {@code expirationMinutes} from ever being set via their setters. The
 * {@link Environment} needed for the fail-fast check is obtained instead via
 * {@link EnvironmentAware}, which keeps this class eligible for ordinary
 * JavaBean-style property binding.
 */
@Component
@ConfigurationProperties(prefix = "security.jwt")
@Getter
@Setter
public class JwtProperties implements EnvironmentAware {

    /**
     * Default secret shipped in {@code application.properties} for local
     * development convenience only. Must never be used outside dev/test.
     */
    static final String DEFAULT_DEV_SECRET =
            "change-me-in-prod-please-this-is-a-dev-only-default-secret-value";

    private static final int MIN_SECRET_BYTES = 32;

    private String secret;
    private long expirationMinutes;

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " UTF-8 bytes (HS256 requires a >=256-bit key).");
        }

        if (DEFAULT_DEV_SECRET.equals(secret) && !isDevOrTestProfileActive()) {
            throw new IllegalStateException(
                    "security.jwt.secret is set to the default dev-only placeholder value."
                            + " Set JWT_SECRET to a real secret outside the 'dev'/'test' profiles.");
        }
    }

    private boolean isDevOrTestProfileActive() {
        for (String profile : environment.getActiveProfiles()) {
            if ("dev".equals(profile) || "test".equals(profile)) {
                return true;
            }
        }
        return false;
    }
}
