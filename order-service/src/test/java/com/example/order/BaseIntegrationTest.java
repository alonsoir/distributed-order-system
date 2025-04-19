package com.example.order;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class BaseIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseIntegrationTest.class);

    // Static and reusable containers
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("orders")
            .withUsername("root")
            .withPassword("root")
            .withReuse(true) // Enable reuse
            .withLogConsumer(new Slf4jLogConsumer(LOGGER).withPrefix("mysql"));

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7")
            .withExposedPorts(6379)
            .withReuse(true) // Enable reuse
            .withLogConsumer(new Slf4jLogConsumer(LOGGER).withPrefix("redis"));

    @BeforeAll
    static void startContainers() {
        // Start containers only if not already running
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
    }

    @AfterAll
    static void stopContainers() {
        // Stop containers only if explicitly needed (e.g., in CI with reuse disabled)
        // With reuse=true, containers are typically left running
        if (System.getProperty("testcontainers.reuse.enable", "true").equals("false")) {
            MYSQL.stop();
            REDIS.stop();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getFirstMappedPort() + "/orders");
        registry.add("spring.r2dbc.username", MYSQL::getUsername);
        registry.add("spring.r2dbc.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }
}