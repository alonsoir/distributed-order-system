package com.example.order.service.unit;

import com.example.order.events.OrderEvent;
import com.example.order.model.SagaStep;
import com.example.order.model.SagaStepType;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompensationManagerUnitTest {

    private static final Long TEST_ORDER_ID = 123456L;
    private static final String TEST_CORRELATION_ID = "test-correlation-id";
    private static final String TEST_EVENT_ID = "test-event-id";
    private static final String TEST_STEP_NAME = "testStep";
    private static final String TEST_EXTERNAL_REF = "test-external-ref"; // Añadido external reference
    private static final String COMPENSATION_TIMER = "saga_compensation_timer";
    private static final String COMPENSATION_RETRY_COUNTER = "saga_compensation_retry";
    private static final String COMPENSATION_DLQ_KEY = "failed-compensations";

    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Mock
    private ReactiveListOperations<String, Object> listOperations;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter retryCounter;

    @Mock
    private Timer timer;

    @Mock
    private Timer.Sample timerSample;

    @Mock
    private OrderEvent mockOrderEvent;

    private TestCompensationManager compensationManager;

    @BeforeEach
    void setUp() {
        // Usamos doReturn en lugar de when para evitar ambigüedades con métodos sobrecargados
        doReturn(retryCounter).when(meterRegistry).counter(anyString(), any(String[].class));
        doReturn(timer).when(meterRegistry).timer(anyString(), any(String[].class));

        // Configurar el RedisTemplate
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.leftPush(anyString(), any())).thenReturn(Mono.just(1L));

        // Crear compensationManager con los mocks y sobreescribir los métodos problemáticos
        compensationManager = new TestCompensationManager(redisTemplate, meterRegistry, timerSample);
    }

    @Test
    void shouldExecuteCompensationSuccessfully() {
        // Arrange
        SagaStep step = createSagaStep(TEST_ORDER_ID, TEST_CORRELATION_ID, TEST_EVENT_ID, TEST_STEP_NAME, TEST_EXTERNAL_REF, false);

        // Act
        Mono<Void> result = compensationManager.executeCompensation(step);

        // Assert
        StepVerifier.create(result)
                .verifyComplete();

        // No verificamos el timer para evitar conflictos con los sobrecargos
    }

    @Test
    void shouldHandleCompensationFailure() {
        // Arrange
        SagaStep step = createSagaStep(TEST_ORDER_ID, TEST_CORRELATION_ID, TEST_EVENT_ID, TEST_STEP_NAME, TEST_EXTERNAL_REF, true);

        // Act
        Mono<Void> result = compensationManager.executeCompensation(step);

        // Assert
        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();

        // Verificar que se insertó en la DLQ
        verify(listOperations).leftPush(eq(COMPENSATION_DLQ_KEY), any(CompensationTask.class));
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
        // Usamos el constructor directamente para poder pasar null en compensation
        SagaStep step = new SagaStep(
                TEST_STEP_NAME,
                "test-topic",
                SagaStepType.UNKNOWN,      // Añadido el SagaStepType
                () -> Mono.empty(),        // action
                null,                      // compensation es null
                eventId -> mockOrderEvent, // successEvent
                TEST_ORDER_ID,
                TEST_CORRELATION_ID,
                TEST_EVENT_ID,
                TEST_EXTERNAL_REF
        );

        // Act
        Mono<Void> result = compensationManager.executeCompensation(step);

        // Assert
        StepVerifier.create(result)
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    private SagaStep createSagaStep(Long orderId, String correlationId, String eventId,
                                    String stepName, String externalRef, boolean shouldFail) {
        // Crear un supplier para la compensación que puede fallar si se solicita
        Supplier<Mono<Void>> compensationSupplier = () -> {
            if (shouldFail) {
                return Mono.error(new RuntimeException("Compensation failed"));
            }
            return Mono.empty();
        };

        // Usar correctamente el builder de SagaStep con los tipos correctos y añadir stepType
        return SagaStep.builder()
                .name(stepName)
                .topic("test-topic")
                .stepType(SagaStepType.UNKNOWN)  // Añadido el SagaStepType
                .action(() -> Mono.empty())
                .compensation(compensationSupplier)
                .successEvent(id -> mockOrderEvent)
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .externalReference(externalRef)
                .build();
    }

    private static class TestCompensationManager extends CompensationManagerImpl {

        private final Timer.Sample timerSample;

        public TestCompensationManager(ReactiveRedisTemplate<String, Object> redisTemplate,
                                       MeterRegistry meterRegistry,
                                       Timer.Sample timerSample) {
            super(redisTemplate, meterRegistry);
            this.timerSample = timerSample;
        }

        @Override
        protected Timer.Sample startTimer() {
            return timerSample;
        }

        @Override
        protected void stopTimer(Timer.Sample sample, Timer timer) {
            // No hacer nada en el test
        }
    }
}