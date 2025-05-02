package com.example.order.service.integration;

import com.example.order.domain.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;

@DataR2dbcTest
@Testcontainers
@ActiveProfiles("integration")
class DatabaseClientIntegrationTest {

    @Container
    private static final MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("orders")
                    .withUsername("root")
                    .withPassword("root")
                    .withInitScript("schema.sql");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DatabaseClient databaseClient;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url",
                () -> "r2dbc:mysql://" + mysql.getHost() +
                        ":" + mysql.getFirstMappedPort() + "/orders?sslMode=DISABLED");
        r.add("spring.r2dbc.username", mysql::getUsername);
        r.add("spring.r2dbc.password", mysql::getPassword);
    }

    @AfterEach
    void cleanup() {
        databaseClient
                .sql("DELETE FROM orders")
                .then()
                .block();
    }

    @Test
    void printBeans() {
        String[] beanNames = context.getBeanDefinitionNames();
        Arrays.sort(beanNames);
        for (String beanName : beanNames) {
            System.out.println(beanName);
        }
    }

    @Test
    void shouldInsertAndRetrieveOrder() {
        Long orderId = 1L;
        String corr = "corr-123";
        String status = "pending";

        Mono<Void> insert = databaseClient
                .sql("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :st, :corr)")
                .bind("id", orderId)
                .bind("st", status)
                .bind("corr", corr)
                .then();

        Mono<Order> fetch = databaseClient
                .sql("SELECT id, status, correlation_id FROM orders WHERE id = :id")
                .bind("id", orderId)
                .map(r -> new Order(
                        r.get("id", Long.class),
                        r.get("status", String.class),
                        r.get("correlation_id", String.class)))
                .one();

        StepVerifier.create(insert.then(fetch))
                .expectNextMatches(o ->
                        o.id().equals(orderId) &&
                                o.status().equals(status) &&
                                o.correlationId().equals(corr)
                )
                .verifyComplete();
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {
        // Empty config with EnableAutoConfiguration to help with component scanning
    }
}