package com.seatvault.seat_vault.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class JwtPropertiesTest {

    private static final String DEFAULT_SECRET =
            "change-me-in-prod-please-this-is-a-dev-only-default-secret-value";
    private static final String VALID_CUSTOM_SECRET =
            "a-custom-secret-that-is-definitely-at-least-32-bytes-long";
    private static final String SHORT_SECRET = "too-short";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultSecretWithNoActiveProfileFailsToStart() {
        contextRunner
                .withPropertyValues(
                        "security.jwt.secret=" + DEFAULT_SECRET,
                        "security.jwt.expiration-minutes=60")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void defaultSecretWithDevProfileStartsFine() {
        contextRunner
                .withPropertyValues(
                        "security.jwt.secret=" + DEFAULT_SECRET,
                        "security.jwt.expiration-minutes=60",
                        "spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void defaultSecretWithTestProfileStartsFine() {
        contextRunner
                .withPropertyValues(
                        "security.jwt.secret=" + DEFAULT_SECRET,
                        "security.jwt.expiration-minutes=60",
                        "spring.profiles.active=test")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void tooShortCustomSecretFailsRegardlessOfProfile() {
        contextRunner
                .withPropertyValues(
                        "security.jwt.secret=" + SHORT_SECRET,
                        "security.jwt.expiration-minutes=60",
                        "spring.profiles.active=dev")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void validCustomSecretStartsFineWithNoProfile() {
        contextRunner
                .withPropertyValues(
                        "security.jwt.secret=" + VALID_CUSTOM_SECRET,
                        "security.jwt.expiration-minutes=60")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    @Import(PropertyPlaceholderAutoConfiguration.class)
    static class TestConfig {
    }
}
