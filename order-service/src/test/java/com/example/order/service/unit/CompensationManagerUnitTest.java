package com.example.order.service.unit;

import com.example.order.model.SagaStep;
import com.example.order.service.CompensationManager;
import com.example.order.service.CompensationManagerImpl;
import com.example.order.service.CompensationTask;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompensationManagerUnitTest {

    private static final Long TEST_ORDER_ID = 123456L;
    private static final String TEST_CORRELATION_ID = "test-correlation-id";
    private static final String TEST_EVENT_ID = "test-event-id";
    private static final String TEST_STEP_NAME = "testStep";

    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Mock
    private ReactiveListOperations<String, Object> listOperations;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter retryCounter;

    @Mock
    private Timer.Sample timerSample;

    @Mock
    private Timer timer;

    private CompensationManager compensationManager;

    @BeforeEach
    void setUp() {
        // Configurar el MeterRegistry para devolver contadores y timers
        // Especificamos claramente qué sobrecarga del método queremos usar
        when(meterRegistry.counter(anyString(), any(Iterable.class))).thenReturn(retryCounter);
        when(meterRegistry.timer(anyString(), any(Iterable.class))).thenReturn(timer);

        // Usamos método estático de Timer correctamente
        Timer.Sample sample = Timer.start();
        when(Timer.start(meterRegistry)).thenReturn(sample);

        // Configurar el RedisTemplate
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.leftPush(anyString(), any())).thenReturn(Mono.just(1L));

        // Crear compensationManager con los mocks
        compensationManager = new CompensationManagerImpl(redisTemplate, meterRegistry);
    }

    @Test
    void shouldExecuteCompensationSuccessfully() {
        // Arrange
        SagaStep step = createSagaStep(TEST_ORDER_ID, TEST_CORRELATION_ID, TEST_EVENT_ID, TEST_STEP_NAME, false);

        // Act
        Mono<Void> result = compensationManager.executeCompensation(step);

        // Assert
        StepVerifier.create(result)
                .verifyComplete();

        // Verificar que se detuvo el timer
        verify(timerSample).stop(eq(timer));
    }

    @Test
    void shouldHandleCompensationFailure() {
        // Arrange
        SagaStep step = createSagaStep(TEST_ORDER_ID, TEST_CORRELATION_ID, TEST_EVENT_ID, TEST_STEP_NAME, true);

        // Act
        Mono<Void> result = compensationManager.executeCompensation(step);

        // Assert
        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();

        // Verificar que se detuvo el timer y se insertó en la DLQ
        verify(timerSample).stop(eq(timer));
        // Aquí verificamos con la clase CompensationTask existente
        verify(listOperations).leftPush(eq("failed-compensations"), any(CompensationTask.class));
    }

    @Test
    void shouldRejectNullSagaStep() {
        // Act
        Mono<Void> result = compensationManager.executeCompensation(null);

        // Assert
        StepVerifier.create(result)
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void shouldRejectSagaStepWithNullCompensation() {
        // Arrange
        SagaStep step = SagaStep.builder()
                .name(TEST_STEP_NAME)
                .orderId(TEST_ORDER_ID)
                .correlationId(TEST_CORRELATION_ID)
                .eventId(TEST_EVENT_ID)
                .build(); // Sin compensación

        // Act
        Mono<Void> result = compensationManager.executeCompensation(step);

        // Assert
        StepVerifier.create(result)
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    private SagaStep createSagaStep(Long orderId, String correlationId, String eventId, String stepName, boolean shouldFail) {
        return SagaStep.builder()
                .name(stepName)
                .topic("test-topic")
                .action(() -> Mono.empty())
                .compensation(() -> {
                    if (shouldFail) {
                        return Mono.error(new RuntimeException("Compensation failed"));
                    }
                    return Mono.empty();
                })
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();
    }
}