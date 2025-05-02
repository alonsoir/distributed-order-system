package com.example.order.service.unit;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@ActiveProfiles("unit")
public class SimpleTestcontainersUnitTest {

    @Container
    private static final MySQLContainer<?> mysqlContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    private static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Test
    void shouldStartContainers() {
        assertTrue(mysqlContainer.isRunning(), "MySQL container should be running");
        assertTrue(redisContainer.isRunning(), "Redis container should be running");
    }
}