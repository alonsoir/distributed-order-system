package com.example.order.service.integration;

import com.example.order.model.SagaStep;
import com.example.order.service.CompensationManager;
import com.example.order.service.CompensationManagerImpl;
import com.example.order.service.CompensationTask;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@Testcontainers
@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompensationManagerIntegrationTest {

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Autowired
    private CompensationManager compensationManager;

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    private final AtomicInteger compensationCallCounter = new AtomicInteger(0);
    private final AtomicBoolean shouldCompensationFail = new AtomicBoolean(false);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public MeterRegistry testMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @BeforeEach
    void setUp() {
        compensationCallCounter.set(0);
        shouldCompensationFail.set(false);

        // Limpiamos la cola de compensaciones fallidas
        redisTemplate.opsForList().trim("failed-compensations", 0, 0)
                .then(redisTemplate.opsForList().leftPop("failed-compensations"))
                .subscribe();
    }

    @Test
    void shouldExecuteCompensationSuccessfully() {
        // Arrange
        SagaStep step = createTestSagaStep(false);

        // Act & Assert
        StepVerifier.create(compensationManager.executeCompensation(step))
                .verifyComplete();

        // Verificar que la compensación se ejecutó exactamente una vez
        assert compensationCallCounter.get() == 1;
    }

    @Test
    void shouldHandleCompensationFailureAndPushToDLQ() {
        // Arrange
        shouldCompensationFail.set(true);
        SagaStep step = createTestSagaStep(true);

        // Act & Assert
        StepVerifier.create(compensationManager.executeCompensation(step))
                .expectError(RuntimeException.class)
                .verify();

        // Verificar que la compensación se intentó ejecutar
        assert compensationCallCounter.get() > 0;

        // Verificar que se guardó en la DLQ
        StepVerifier.create(redisTemplate.opsForList().size("failed-compensations"))
                .expectNext(1L)
                .verifyComplete();

        // Verificar que el elemento en la DLQ es del tipo correcto
        StepVerifier.create(redisTemplate.opsForList().range("failed-compensations", 0, 0))
                .expectNextMatches(item -> item instanceof CompensationTask)
                .verifyComplete();
    }

    @Test
    void shouldRetryCompensationBeforeFailing() {
        // Arrange
        final AtomicInteger attempts = new AtomicInteger(0);

        SagaStep step = SagaStep.builder()
                .name("retryTest")
                .topic("test-topic")
                .action(() -> Mono.empty())
                .compensation(() -> {
                    compensationCallCounter.incrementAndGet();
                    attempts.incrementAndGet();
                    if (attempts.get() <= 2) { // Falla las primeras 2 veces
                        return Mono.error(new io.lettuce.core.RedisException("Temporary Redis failure"));
                    }
                    return Mono.empty(); // Éxito en el tercer intento
                })
                .orderId(123L)
                .correlationId("test-correlation")
                .eventId("test-event")
                .build();

        // Act & Assert
        StepVerifier.create(compensationManager.executeCompensation(step))
                .verifyComplete();

        // Verificar que se realizaron múltiples intentos
        assert compensationCallCounter.get() > 1;
    }

    private SagaStep createTestSagaStep(boolean expectFailure) {
        return SagaStep.builder()
                .name("testCompensation")
                .topic("test-topic")
                .action(() -> Mono.empty())
                .compensation(() -> {
                    compensationCallCounter.incrementAndGet();
                    if (shouldCompensationFail.get() && expectFailure) {
                        return Mono.error(new RuntimeException("Compensation failed"));
                    }
                    return Mono.empty();
                })
                .orderId(123L)
                .correlationId(UUID.randomUUID().toString())
                .eventId(UUID.randomUUID().toString())
                .build();
    }
}