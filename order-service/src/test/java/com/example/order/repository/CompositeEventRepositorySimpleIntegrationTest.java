package com.example.order.repository;

import com.example.order.repository.events.ProcessedEventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
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

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Test de integración simplificado para CompositeEventRepository
 * Se enfoca en probar solo la funcionalidad esencial sin las complejidades de autoconfiguración
 */
@Testcontainers
@SpringBootTest(
        classes = {CompositeEventRepositorySimpleIntegrationTest.TestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("integration-test")
public class CompositeEventRepositorySimpleIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CompositeEventRepositorySimpleIntegrationTest.class);

    @Container
    static MySQLContainer<?> mySqlContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withExposedPorts(6379)
            .withReuse(true);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () ->
                String.format("r2dbc:mysql://%s:%d/%s",
                        mySqlContainer.getHost(),
                        mySqlContainer.getFirstMappedPort(),
                        mySqlContainer.getDatabaseName()));
        registry.add("spring.r2dbc.username", mySqlContainer::getUsername);
        registry.add("spring.r2dbc.password", mySqlContainer::getPassword);

        registry.add("spring.redis.host", redisContainer::getHost);
        registry.add("spring.redis.port", redisContainer::getFirstMappedPort);

        // Deshabilitar configuraciones problemáticas
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("resilience4j.fallback.enabled", () -> "false");
        registry.add("management.endpoints.enabled-by-default", () -> "false");
    }

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private R2dbcEntityTemplate r2dbcTemplate;

    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    private CompositeEventRepository compositeEventRepository;

    @BeforeEach
    void setUp() {
        // Limpiar datos antes de cada prueba
        Mono.when(
                r2dbcTemplate.getDatabaseClient().sql("DELETE FROM processed_events").then(),
                r2dbcTemplate.getDatabaseClient().sql("DELETE FROM transaction_locks").then(),
                redisTemplate.delete(redisTemplate.keys("*"))
        ).block(Duration.ofSeconds(5));

        // Disable internal retries for ProcessedEventRepository during these tests
        processedEventRepository.setInternalRetryEnabled(false);

        // Crear instancia simplificada para el test
        compositeEventRepository = new CompositeEventRepository(
                new SimpleMeterRegistry(),
                CircuitBreakerRegistry.ofDefaults(),
                processedEventRepository,
                mock(com.example.order.repository.orders.OrderRepository.class),
                mock(com.example.order.repository.saga.SagaFailureRepository.class),
                mock(com.example.order.repository.events.EventHistoryRepository.class),
                mock(com.example.order.repository.transactions.TransactionLockRepository.class)
        );
    }

    @Test
    @DisplayName("Test básico: Debe marcar y verificar eventos procesados")
    void testBasicEventProcessing() {
        String eventId = "test-event-" + UUID.randomUUID();

        // Primero verificar que el evento no está procesado
        StepVerifier.create(compositeEventRepository.isEventProcessed(eventId))
                .expectNext(false)
                .verifyComplete();

        // Marcar el evento como procesado
        StepVerifier.create(compositeEventRepository.markEventAsProcessed(eventId))
                .verifyComplete();

        // Verificar que ahora está marcado como procesado
        StepVerifier.create(compositeEventRepository.isEventProcessed(eventId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("Test de validación: Debe rechazar parámetros inválidos")
    void testValidation() {
        // Test con resourceId nulo
        StepVerifier.create(compositeEventRepository.acquireTransactionLock(null, "corr-id", 10))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Test con resourceId vacío
        StepVerifier.create(compositeEventRepository.acquireTransactionLock("", "corr-id", 10))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Test con correlationId nulo
        StepVerifier.create(compositeEventRepository.acquireTransactionLock("resource-id", null, 10))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Test de infraestructura: Debe poder conectarse a bases de datos")
    void testDatabaseConnectivity() {
        // Test de conectividad a MySQL
        StepVerifier.create(r2dbcTemplate.getDatabaseClient().sql("SELECT 1").fetch().first())
                .expectNextCount(1)
                .verifyComplete();

        // Test de conectividad a Redis
        StepVerifier.create(redisTemplate.hasKey("test-key"))
                .expectNext(false)
                .verifyComplete();
    }

    /**
     * Configuración mínima para el test que evita problemas de autoconfiguración
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {
            // Excluir autoconfiguraciones problemáticas
            org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration.class,
            org.springframework.cloud.autoconfigure.RefreshAutoConfiguration.class
    })
    static class TestConfig {

        @Bean
        @Primary
        public SimpleMeterRegistry testMeterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        public CircuitBreakerRegistry testCircuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }
    }
}