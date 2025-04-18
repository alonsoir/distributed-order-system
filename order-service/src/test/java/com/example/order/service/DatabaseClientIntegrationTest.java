package com.example.order.service;

import com.example.order.domain.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SpringBootTest
@Testcontainers
class DatabaseClientIntegrationTest {

    @Container
    private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("orders")
            .withUsername("root")
            .withPassword("root")
            .withInitScript("schema.sql");

    @Autowired
    private DatabaseClient databaseClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:mysql://" + mysql.getHost() + ":" + mysql.getFirstMappedPort() + "/orders");
        registry.add("spring.r2dbc.username", mysql::getUsername);
        registry.add("spring.r2dbc.password", mysql::getPassword);
    }

    @Test
    void shouldInsertAndRetrieveOrder() {
        Long orderId = 1L;
        String correlationId = "corr-123";
        String status = "pending";

        Mono<Void> insert = databaseClient.sql("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :status, :correlationId)")
                .bind("id", orderId)
                .bind("status", status)
                .bind("correlationId", correlationId)
                .then();

        Mono<Order> retrieve = databaseClient.sql("SELECT id, status, correlation_id FROM orders WHERE id = :id")
                .bind("id", orderId)
                .map(row -> new Order(row.get("id", Long.class), row.get("status", String.class), row.get("correlation_id", String.class)))
                .one();

        StepVerifier.create(insert.then(retrieve))
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals(status) &&
                                order.correlationId().equals(correlationId))
                .verifyComplete();
    }
}