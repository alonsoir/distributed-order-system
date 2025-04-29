package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.events.OrderFailedEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerUnitTest {

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
    private SagaOrchestrator sagaOrchestrator;
    @Mock
    private CircuitBreaker circuitBreaker;

    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        // Configuración adicional si es necesaria
    }

    @Test
    void shouldReturnFallbackWhenCircuitBreakerIsOpen() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(false);
        when(sagaOrchestrator.publishFailedEvent(any(OrderFailedEvent.class))).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order -> order.status().equals("failed") && order.id().equals(orderId))
                .verifyComplete();

        verify(circuitBreaker).tryAcquirePermission(); // Solo una vez
        verify(sagaOrchestrator).publishFailedEvent(any(OrderFailedEvent.class));
        verify(sagaOrchestrator, never()).executeOrderSaga(anyLong(), anyInt(), anyDouble(), anyString());
    }

    @Test
    void shouldApplyCircuitBreakerOnSuccessfulOrderProcessing() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;
        String correlationId = "test-correlation-id";
        Order order = new Order(orderId, "completed", correlationId);

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        when(sagaOrchestrator.executeOrderSaga(eq(orderId), eq(quantity), eq(amount), anyString()))
                .thenReturn(Mono.just(order));
        when(meterRegistry.counter("orders_success")).thenReturn(mock(Counter.class));

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(o -> o.id().equals(orderId) && o.status().equals("completed"))
                .verifyComplete();

        verify(circuitBreaker, times(2)).tryAcquirePermission(); // Dos veces
        verify(sagaOrchestrator).executeOrderSaga(eq(orderId), eq(quantity), eq(amount), anyString());
        verify(meterRegistry).counter("orders_success");
    }

    /*
    TODO No funciona, más fresco, hay que retomar esto.
    @Test
    void shouldReturnFailedOrderWhenSagaFails() {
        Long orderId = 2L;
        int quantity = 5;
        double amount = 50.0;

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        when(sagaOrchestrator.executeOrderSaga(eq(orderId), eq(quantity), eq(amount), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Saga failed")));
        when(sagaOrchestrator.publishFailedEvent(any())).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order -> order.status().equals("failed") && order.id().equals(orderId))
                .verifyComplete();

        verify(circuitBreaker, times(2)).tryAcquirePermission(); // Dos veces
        verify(sagaOrchestrator, times(1)).publishFailedEvent(any(OrderFailedEvent.class)); // Solo una vez
    }
    */
}