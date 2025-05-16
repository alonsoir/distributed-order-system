package com.example.order.service.integration;

import com.example.order.service.IdGenerator;
import com.example.order.service.InventoryService;
import com.example.order.service.OrderService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

import java.util.UUID;

@SpringBootTest
@Testcontainers
@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderServiceIntegrationTest {

    @Container
    private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("orders")
            .withUsername("root")
            .withPassword("root")
            .withInitScript("schema-tables.sql");

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private IdGenerator idGenerator;

    @BeforeAll
    void setupAll() {

    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:mysql://" + mysql.getHost() + ":" + mysql.getFirstMappedPort() + "/orders");
        registry.add("spring.r2dbc.username", mysql::getUsername);
        registry.add("spring.r2dbc.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    // Configuración de prueba para proporcionar un bean de InventoryService para testing
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public InventoryService testInventoryService() {
            return new TestInventoryService();
        }
    }

    // Implementación de prueba de InventoryService
    static class TestInventoryService implements InventoryService {
        private boolean shouldFail = false;

        public void setShouldFail(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }

        @Override
        public Mono<Void> reserveStock(Long orderId, int quantity) {
            if (shouldFail) {
                return Mono.error(new RuntimeException("Stock not available"));
            }
            return Mono.empty();
        }

        @Override
        public Mono<Void> releaseStock(Long orderId, int quantity) {
            return Mono.empty();
        }
    }

    @Test
    void shouldProcessOrderSuccessfully() {
        // Arrange
        String externalReference = generateUniqueExternalReference();
        int quantity = 10;
        double amount = 100.0;

        // Aseguramos que el servicio de inventario responde correctamente
        ((TestInventoryService) inventoryService).setShouldFail(false);

        // Act & Assert
        StepVerifier.create(orderService.processOrder(externalReference, quantity, amount))
                .expectNextMatches(order ->
                        // Verificamos que se ha asignado un ID (no nulo) y que el estado es "completed"
                        order.id() != null &&
                                order.status().equals("completed") &&
                                // Ahora el correlationId no es el externalReference, sino uno generado internamente
                                order.correlationId() != null)
                .verifyComplete();
    }

    @Test
    void shouldHandleInventoryServiceFailure() {
        // Arrange
        String externalReference = generateUniqueExternalReference();
        int quantity = 10;
        double amount = 100.0;

        // Configuramos el servicio de inventario para que falle
        ((TestInventoryService) inventoryService).setShouldFail(true);

        // Act & Assert
        StepVerifier.create(orderService.processOrder(externalReference, quantity, amount))
                .expectNextMatches(order ->
                        // Verificamos que la orden se ha marcado como fallida
                        order.id() != null &&
                                order.status().equals("failed") &&
                                order.correlationId() != null)
                .verifyComplete();
    }

    @Test
    void shouldRejectInvalidOrderParameters() {
        // Act & Assert - Cantidad negativa
        StepVerifier.create(orderService.processOrder(generateUniqueExternalReference(), 0, 100.0))
                .expectErrorMatches(error ->
                        error instanceof IllegalArgumentException &&
                                error.getMessage().contains("Quantity must be positive"))
                .verify();

        // Act & Assert - Monto negativo
        StepVerifier.create(orderService.processOrder(generateUniqueExternalReference(), 10, -50.0))
                .expectErrorMatches(error ->
                        error instanceof IllegalArgumentException &&
                                error.getMessage().contains("Amount must be non-negative"))
                .verify();

        // Act & Assert - Referencia externa vacía
        StepVerifier.create(orderService.processOrder("", 10, 100.0))
                .expectErrorMatches(error ->
                        error instanceof IllegalArgumentException &&
                                error.getMessage().contains("External reference cannot be null or empty"))
                .verify();
    }

    private String generateUniqueExternalReference() {
        // Utiliza UUID para generar referencias externas únicas
        return "ext-" + UUID.randomUUID().toString();
    }
}