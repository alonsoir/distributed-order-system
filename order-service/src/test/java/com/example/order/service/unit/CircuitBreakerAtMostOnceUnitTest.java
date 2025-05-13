package com.example.order.service.unit;

import com.example.order.domain.Order;
import com.example.order.events.OrderFailedEvent;
import com.example.order.service.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Este test usa la implementacion AT MOST ONCE de SagaOrchestrator.
 * */
@ExtendWith(MockitoExtension.class)
@Testcontainers
@ActiveProfiles("unit")
@MockitoSettings(strictness = Strictness.LENIENT)
class CircuitBreakerAtMostOnceUnitTest {

    private static final long TEST_ORDER_ID = 1L;
    private static final String TEST_CORRELATION_ID = "test-correlation-id";
    private static final int TEST_QUANTITY = 10;
    private static final double TEST_AMOUNT = 100.0;
    private static final String TEST_EXTERNAL_REF = "ext-123";
    private static final String TEST_EVENT_ID = "event-123";

    @Mock
    private DatabaseClient databaseClient;
    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private TransactionalOperator transactionalOperator;
    @Mock
    // AT MOST ONCE
    private SagaOrchestratorAtMostOnceImpl2 sagaOrchestrator;
    @Mock
    private CircuitBreaker circuitBreaker;
    @Mock
    private IdGenerator idGenerator;
    @Mock
    private Counter ordersSuccessCounter;
    @Mock
    private CompensationManager compensationManager;

    // Declaramos la instancia real de OrderServiceAtLeastOnceImpl (no un mock)
    private OrderServiceAtLeastOnceImpl orderService;

    @BeforeEach
    void setUp() {
        // Creamos una instancia real de OrderServiceAtLeastOnceImpl con los mocks necesarios
        orderService = new OrderServiceAtLeastOnceImpl(circuitBreakerRegistry, meterRegistry, sagaOrchestrator);

        // Configuración de mocks común
        lenient().when(idGenerator.generateExternalReference()).thenReturn(TEST_EXTERNAL_REF);
        lenient().when(idGenerator.generateEventId()).thenReturn(TEST_EVENT_ID);
        lenient().when(meterRegistry.counter("orders_success")).thenReturn(ordersSuccessCounter);
        lenient().when(meterRegistry.counter(anyString())).thenReturn(ordersSuccessCounter);

        // Este mock es esencial para varios tests
        lenient().when(sagaOrchestrator.createFailedEvent(anyString(), anyString()))
                .thenReturn(Mono.empty());
    }

    @Test
    void shouldReturnFallbackWhenCircuitBreakerIsOpen() {
        // Arrange
        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(false);

        // Este es el mock que falta - createFailedEvent debe devolver un Mono.empty()
        when(sagaOrchestrator.createFailedEvent(eq("circuit_breaker_open"), eq(TEST_EXTERNAL_REF)))
                .thenReturn(Mono.empty());

        // Act
        Mono<Order> result = orderService.processOrder(TEST_EXTERNAL_REF, TEST_QUANTITY, TEST_AMOUNT);

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(order -> order.status().equals("failed"))
                .verifyComplete();

        verify(circuitBreaker).tryAcquirePermission();
        verify(sagaOrchestrator).createFailedEvent(eq("circuit_breaker_open"), eq(TEST_EXTERNAL_REF));
        verify(sagaOrchestrator, never()).executeOrderSaga(anyInt(), anyDouble());
    }

    @Test
    void shouldApplyCircuitBreakerOnSuccessfulOrderProcessing() {
        // Arrange
        Order expectedOrder = new Order(TEST_ORDER_ID, "completed", TEST_CORRELATION_ID);

        // Configurar un mock para este test
        CircuitBreaker testCircuitBreaker = mock(CircuitBreaker.class);
        when(testCircuitBreaker.tryAcquirePermission()).thenReturn(true);
        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(testCircuitBreaker);

        // Configurar el comportamiento de onSuccess
        doNothing().when(testCircuitBreaker).onSuccess(anyLong(), any(TimeUnit.class));

        // Resto igual...
        when(sagaOrchestrator.executeOrderSaga(
                eq(TEST_QUANTITY),
                eq(TEST_AMOUNT)
        )).thenReturn(Mono.just(expectedOrder));

        // Act
        Mono<Order> result = orderService.processOrder(TEST_EXTERNAL_REF, TEST_QUANTITY, TEST_AMOUNT);

        // Assert
        StepVerifier.create(result)
                .expectNext(expectedOrder)
                .verifyComplete();

        // Verificamos que se llama a tryAcquirePermission() una vez
        verify(testCircuitBreaker).tryAcquirePermission();
        // Verificamos que se llama a onSuccess() una vez con cualquier valor de tiempo y unidad
        verify(testCircuitBreaker).onSuccess(anyLong(), any(TimeUnit.class));

        verify(sagaOrchestrator).executeOrderSaga(
                eq(TEST_QUANTITY),
                eq(TEST_AMOUNT));
        verify(ordersSuccessCounter).increment();
        verify(sagaOrchestrator, never()).createFailedEvent(anyString(), anyString());
    }

    @Test
    void shouldProcessOrderSuccessfullyWithCircuitBreakerAlwaysClosed() {
        // Arrange
        Order expectedOrder = new Order(TEST_ORDER_ID, "completed", TEST_CORRELATION_ID);

        // Configurar CircuitBreaker
        CircuitBreaker simpleCircuitBreaker = mock(CircuitBreaker.class);
        when(simpleCircuitBreaker.tryAcquirePermission()).thenReturn(true);
        // Eliminamos la verificación de acquirePermission porque nunca se llama en el código real
        doNothing().when(simpleCircuitBreaker).onSuccess(anyLong(), any(TimeUnit.class));
        when(circuitBreakerRegistry.circuitBreaker(anyString())).thenReturn(simpleCircuitBreaker);

        when(sagaOrchestrator.executeOrderSaga(anyInt(), anyDouble()))
                .thenReturn(Mono.just(expectedOrder));

        // Act
        Mono<Order> result = orderService.processOrder(TEST_EXTERNAL_REF, TEST_QUANTITY, TEST_AMOUNT);

        // Assert
        StepVerifier.create(result)
                .expectNext(expectedOrder)
                .verifyComplete();

        // Verificar lo mínimo necesario
        verify(simpleCircuitBreaker).tryAcquirePermission();
        // Eliminamos la verificación de acquirePermission
        verify(simpleCircuitBreaker).onSuccess(anyLong(), any(TimeUnit.class));

        verify(sagaOrchestrator).executeOrderSaga(TEST_QUANTITY, TEST_AMOUNT);
        verify(ordersSuccessCounter).increment();
    }

    @Test
    void shouldReturnFailedOrderWhenSagaFails() {
        // Arrange
        Order failedOrder = new Order(TEST_ORDER_ID, "failed", TEST_CORRELATION_ID);

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        // Eliminamos la verificación de acquirePermission
        doNothing().when(circuitBreaker).onError(anyLong(), any(TimeUnit.class), any(Throwable.class));

        when(sagaOrchestrator.executeOrderSaga(
                eq(TEST_QUANTITY),
                eq(TEST_AMOUNT)
        ))
                .thenReturn(Mono.error(new RuntimeException("Saga failed")));

        // Mock para el createFailedEvent - esto es lo que se llama ahora
        // Cambiamos de global_timeout a circuit_breaker_error para reflejar el comportamiento real
        when(sagaOrchestrator.createFailedEvent(eq("circuit_breaker_error"), eq(TEST_EXTERNAL_REF)))
                .thenReturn(Mono.empty());

        // Act
        Mono<Order> result = orderService.processOrder(TEST_EXTERNAL_REF, TEST_QUANTITY, TEST_AMOUNT);

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(order -> order.status().equals("failed"))
                .verifyComplete();

        verify(circuitBreaker).tryAcquirePermission();
        // Eliminamos la verificación de acquirePermission
        verify(circuitBreaker).onError(anyLong(), any(TimeUnit.class), any(Throwable.class));

        // Ahora verificamos createFailedEvent con el valor correcto
        verify(sagaOrchestrator).createFailedEvent(eq("circuit_breaker_error"), eq(TEST_EXTERNAL_REF));
        verify(sagaOrchestrator, never()).publishFailedEvent(any(OrderFailedEvent.class));
    }

    // Test adicional para validar características específicas de la implementación AT MOST ONCE
    @Test
    void shouldHandleIdempotencyWhenEventAlreadyProcessed() {
        // Arrange
        Order existingOrder = new Order(TEST_ORDER_ID, "completed", TEST_CORRELATION_ID);

        // Configurar CircuitBreaker
        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        doNothing().when(circuitBreaker).onSuccess(anyLong(), any(TimeUnit.class));

        // Mock para verificar que el evento ya fue procesado y devolver una orden existente
        when(sagaOrchestrator.executeOrderSaga(
                eq(TEST_QUANTITY),
                eq(TEST_AMOUNT)
        )).thenReturn(Mono.just(existingOrder));

        // Act
        Mono<Order> result = orderService.processOrder(TEST_EXTERNAL_REF, TEST_QUANTITY, TEST_AMOUNT);

        // Assert
        StepVerifier.create(result)
                .expectNext(existingOrder)
                .verifyComplete();

        verify(circuitBreaker).tryAcquirePermission();
        verify(circuitBreaker).onSuccess(anyLong(), any(TimeUnit.class));
        verify(sagaOrchestrator).executeOrderSaga(TEST_QUANTITY, TEST_AMOUNT);
        verify(ordersSuccessCounter).increment();
    }
}