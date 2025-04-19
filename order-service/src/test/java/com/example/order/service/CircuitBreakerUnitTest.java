package com.example.order.service;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private CircuitBreaker orderCircuitBreaker;

    @Mock
    private CircuitBreaker redisCircuitBreaker;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private TransactionalOperator transactionalOperator;

    @Mock
    private Counter successCounter;

    @Mock
    private Counter failedCounter;

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        reset(databaseClient, redisTemplate, inventoryService, circuitBreakerRegistry, orderCircuitBreaker, redisCircuitBreaker, meterRegistry, transactionalOperator, successCounter, failedCounter);
    }

    @Test
    void shouldApplyCircuitBreakerOnSuccessfulOrderProcessing() {
        // Arrange
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        // Mock dependencies
        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(orderCircuitBreaker);
        when(circuitBreakerRegistry.circuitBreaker("redisOperations")).thenReturn(redisCircuitBreaker); // Fixed to match OrderService
        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        // Mock database interactions
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Mock redis interactions
        @SuppressWarnings("unchecked")
        ReactiveStreamOperations<String, Object, Object> streamOps = mock(ReactiveStreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(anyString(), any(Map.class))).thenReturn(Mono.just(RecordId.autoGenerate()));

        // Mock metrics
        when(meterRegistry.counter("orders_success")).thenReturn(successCounter);
        when(meterRegistry.counter("orders_failed")).thenReturn(failedCounter);
        when(meterRegistry.counter("saga_step_success", "step", "reserveStock")).thenReturn(successCounter);
        when(meterRegistry.counter("saga_step_failed", "step", "reserveStock")).thenReturn(failedCounter);

        // Mock transactional operator
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(order -> order.id().equals(orderId) && order.status().equals("completed"))
                .verifyComplete();

        verify(circuitBreakerRegistry, times(1)).circuitBreaker("orderProcessing");
        verify(circuitBreakerRegistry, times(1)).circuitBreaker("redis");
        verify(inventoryService, times(1)).reserveStock(orderId, quantity);
        verify(meterRegistry, times(1)).counter("orders_success");
        verify(meterRegistry, times(1)).counter("saga_step_success", "step", "reserveStock");
    }

    @Test
    void shouldReturnFallbackWhenCircuitBreakerIsOpen() {
        // Arrange
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        // Simulate circuit breaker open
        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(orderCircuitBreaker);
        when(circuitBreakerRegistry.circuitBreaker("redisOperations")).thenReturn(redisCircuitBreaker); // Fixed to match OrderService
        when(orderCircuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);

        // Mock database interactions
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Mock redis interactions
        @SuppressWarnings("unchecked")
        ReactiveStreamOperations<String, Object, Object> streamOps = mock(ReactiveStreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(anyString(), any(Map.class))).thenReturn(Mono.just(RecordId.autoGenerate()));

        // Mock metrics
        when(meterRegistry.counter("orders_success")).thenReturn(successCounter);
        when(meterRegistry.counter("orders_failed")).thenReturn(failedCounter);

        // Act
        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(order -> order.id().equals(orderId) && order.status().equals("failed"))
                .verifyComplete();

        verify(circuitBreakerRegistry, times(1)).circuitBreaker("orderProcessing");
        verify(circuitBreakerRegistry, times(1)).circuitBreaker("redis");
        verify(meterRegistry, times(1)).counter("orders_failed");
        verifyNoInteractions(inventoryService);
    }
}