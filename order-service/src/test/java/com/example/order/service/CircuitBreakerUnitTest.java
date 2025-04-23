package com.example.order.service;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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

    @Mock
    private CircuitBreakerConfig circuitBreakerConfig;

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        reset(databaseClient, redisTemplate, inventoryService,
                circuitBreakerRegistry, orderCircuitBreaker, redisCircuitBreaker,
                meterRegistry, transactionalOperator, successCounter, failedCounter, circuitBreakerConfig);

        // -- counters de orders y saga steps (ya tenías estos lenient) --
        lenient().when(meterRegistry.counter("orders_success")).thenReturn(successCounter);
        lenient().when(meterRegistry.counter("orders_failed")).thenReturn(failedCounter);
        lenient().when(meterRegistry.counter("saga_step_success", "step", "reserveStock"))
                .thenReturn(successCounter);
        lenient().when(meterRegistry.counter("saga_step_failed", "step", "reserveStock"))
                .thenReturn(failedCounter);

        // sólo necesitamos el timer de Redis, el de outbox no entra en estos tests
        Timer mockRedisTimer = mock(Timer.class);
        lenient().when(meterRegistry.timer("event.publish.redis.timer"))
                .thenReturn(mockRedisTimer);

        // counters de publicación de eventos
        lenient().when(meterRegistry.counter("event.publish.redis.success"))
                .thenReturn(successCounter);
        lenient().when(meterRegistry.counter("event.publish.redis.failure"))
                .thenReturn(failedCounter);
        lenient().when(meterRegistry.counter("event.publish.redis.retry"))
                .thenReturn(successCounter);

        // outbox counters (lenient para que no rompan el “zero unnecessary stubbings”)
        lenient().when(meterRegistry.counter("event.publish.outbox.success"))
                .thenReturn(successCounter);
        lenient().when(meterRegistry.counter("event.publish.outbox.failure"))
                .thenReturn(failedCounter);
        lenient().when(meterRegistry.counter("event.publish.outbox.retry"))
                .thenReturn(successCounter);

        // circuit breakers config stub
        lenient().when(orderCircuitBreaker.getCircuitBreakerConfig())
                .thenReturn(circuitBreakerConfig);
        lenient().when(redisCircuitBreaker.getCircuitBreakerConfig())
                .thenReturn(circuitBreakerConfig);
        lenient().when(circuitBreakerConfig.isWritableStackTraceEnabled())
                .thenReturn(true);
    }



    @Test
    @SuppressWarnings("unchecked")
    void shouldApplyCircuitBreakerOnSuccessfulOrderProcessing() {
        // Arrange
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        lenient().when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(orderCircuitBreaker);
        lenient().when(circuitBreakerRegistry.circuitBreaker("redisEventPublishing")).thenReturn(redisCircuitBreaker);
        lenient().when(orderCircuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        lenient().when(orderCircuitBreaker.tryAcquirePermission()).thenReturn(true);
        lenient().when(redisCircuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        lenient().when(redisCircuitBreaker.tryAcquirePermission()).thenReturn(true);
        lenient().when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.then()).thenReturn(Mono.empty());

        ReactiveStreamOperations<String, Object, Object> streamOps = mock(ReactiveStreamOperations.class);
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOps);
        lenient().when(streamOps.add(anyString(), any(Map.class))).thenReturn(Mono.just(RecordId.autoGenerate()));

        lenient().when(meterRegistry.counter("orders_success")).thenReturn(successCounter);
        lenient().when(meterRegistry.counter("orders_failed")).thenReturn(failedCounter);
        lenient().when(meterRegistry.counter("saga_step_success", "step", "reserveStock")).thenReturn(successCounter);
        lenient().when(meterRegistry.counter("saga_step_failed", "step", "reserveStock")).thenReturn(failedCounter);

        lenient().when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(invocation -> {
            Mono<?> mono = invocation.getArgument(0);
            System.out.println("TransactionalOperator called with: " + mono);
            return mono != null ? mono : Mono.empty();
        });

        // Act
        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(order -> order.id().equals(orderId) && order.status().equals("completed"))
                .verifyComplete();

        verify(circuitBreakerRegistry, times(1)).circuitBreaker("orderProcessing");
        verify(circuitBreakerRegistry, atLeast(1)).circuitBreaker("redisEventPublishing");
        verify(inventoryService, times(1)).reserveStock(orderId, quantity);
        verify(meterRegistry, times(1)).counter("orders_success");
        verify(meterRegistry, times(1)).counter("saga_step_success", "step", "reserveStock");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnFallbackWhenCircuitBreakerIsOpen() {
        // Arrange
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        lenient().when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(orderCircuitBreaker);
        lenient().when(circuitBreakerRegistry.circuitBreaker("redisEventPublishing")).thenReturn(redisCircuitBreaker);
        lenient().when(orderCircuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);
        lenient().doThrow(CallNotPermittedException.createCallNotPermittedException(orderCircuitBreaker))
                .when(orderCircuitBreaker).tryAcquirePermission();
        lenient().when(redisCircuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        lenient().when(redisCircuitBreaker.tryAcquirePermission()).thenReturn(true);

        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.then()).thenReturn(Mono.empty());

        ReactiveStreamOperations<String, Object, Object> streamOps = mock(ReactiveStreamOperations.class);
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOps);
        lenient().when(streamOps.add(anyString(), any(Map.class))).thenReturn(Mono.just(RecordId.autoGenerate()));

        lenient().when(meterRegistry.counter("orders_success")).thenReturn(successCounter);
        lenient().when(meterRegistry.counter("orders_failed")).thenReturn(failedCounter);

        lenient().when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(invocation -> {
            Mono<?> mono = invocation.getArgument(0);
            System.out.println("TransactionalOperator called with: " + mono);
            return mono != null ? mono : Mono.empty();
        });

        // Act
        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(order -> order.id().equals(orderId) && order.status().equals("failed"))
                .verifyComplete();

        verify(circuitBreakerRegistry, times(1)).circuitBreaker("orderProcessing");
        verify(circuitBreakerRegistry, atLeast(1)).circuitBreaker("redisEventPublishing");
        verify(meterRegistry, times(1)).counter("orders_failed");
        verifyNoInteractions(inventoryService);
    }
}