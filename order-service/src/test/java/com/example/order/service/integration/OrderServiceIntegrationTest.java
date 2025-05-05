package com.example.order.service.integration;

import com.example.order.service.IdGenerator;
import com.example.order.service.InventoryService;
import com.example.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.*;



@SpringBootTest
@Testcontainers
@ActiveProfiles("integration")
class OrderServiceIntegrationTest {

    @Container
    private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("orders")
            .withUsername("root")
            .withPassword("root")
            .withInitScript("schema.sql");

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private IdGenerator idGenerator;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:mysql://" + mysql.getHost() + ":" + mysql.getFirstMappedPort() + "/orders");
        registry.add("spring.r2dbc.username", mysql::getUsername);
        registry.add("spring.r2dbc.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Test
    void shouldProcessOrderSuccessfully() {
        Long orderId = idGenerator.generateOrderId();
        String externalReference = idGenerator.generateExternalReference();
        int quantity = 10;
        double amount = 100.0;

        when(inventoryService.reserveStock(anyLong(), anyInt())).thenReturn(Mono.empty());

        StepVerifier.create(orderService.processOrder(orderId, externalReference, quantity, amount))
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("completed"))
                .verifyComplete();

        // Verificar que los datos se persistieron
        StepVerifier.create(orderService.createOrder(orderId, "corr-123"))
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("pending"))
                .verifyComplete();
    }
}