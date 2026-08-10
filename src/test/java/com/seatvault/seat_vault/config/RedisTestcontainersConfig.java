package com.seatvault.seat_vault.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Wires a real Redis container into the Spring context. */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfig {

    @Bean
    @ServiceConnection("redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
