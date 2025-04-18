package com.example.order.service;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doNothing;

@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Mock
    private ReactiveStreamOperations<String, Object, Object> streamOperations;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private TransactionalOperator transactionalOperator;

    @Mock
    private CircuitBreaker circuitBreaker;

    @Mock
    private CircuitBreakerConfig circuitBreakerConfig;

    @Mock
    private Counter counter;

    @InjectMocks
    private OrderService orderService;

    private DatabaseClient.GenericExecuteSpec executeSpec;

    @BeforeEach
    void setUp() throws Exception {
        Retry retryMock = mock(Retry.class);
        executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        when(circuitBreaker.executeCallable(any())).thenAnswer(invocation -> invocation.getArgument(0, java.util.concurrent.Callable.class).call());
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(anyString(), any(Map.class))).thenReturn(Mono.just(RecordId.of("1-0")));
        when(circuitBreakerRegistry.circuitBreaker(anyString())).thenReturn(circuitBreaker);
        when(circuitBreaker.getCircuitBreakerConfig()).thenReturn(circuitBreakerConfig);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        doNothing().when(counter).increment();
    }

    @Test
    void shouldCreateOrderSuccessfully() {
        Long orderId = 1L;
        String correlationId = "corr-123";

        Mono<Order> result = orderService.createOrder(orderId, correlationId);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("pending") &&
                                order.correlationId().equals(correlationId))
                .verifyComplete();

        verify(databaseClient, times(4)).sql(anyString()); // Order, outbox, processed_events
        verify(streamOperations).add(eq("orders"), any(Map.class));
    }

    @Test
    void shouldHandleDatabaseErrorAndPublishFailedEvent() {
        Long orderId = 1L;
        String correlationId = "corr-123";
        RuntimeException error = new RuntimeException("DB error");

        when(executeSpec.then()).thenReturn(Mono.error(error));

        Mono<Order> result = orderService.createOrder(orderId, correlationId);

        StepVerifier.create(result)
                .expectError(RetryExhaustedException.class)  // O manejar el error
                .verify();

        verify(streamOperations).add(eq("orders"), any(Map.class)); // Only OrderFailed event
    }

    @Test
    void shouldProcessOrderSuccessfully() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("completed"))
                .verifyComplete();

        verify(inventoryService).reserveStock(orderId, quantity);
        verify(databaseClient, times(5)).sql(anyString()); // Order, outbox, processed_events, outbox (StockReserved), processed_events
        verify(streamOperations, times(2)).add(eq("orders"), any(Map.class)); // OrderCreated + StockReserved
        verify(meterRegistry).counter(eq("orders_success"), any(String[].class));
    }

    @Test
    void shouldReleaseStockOnReserveFailure() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;
        RuntimeException error = new RuntimeException("Stock reservation failed");

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.error(error));
        when(inventoryService.releaseStock(orderId, quantity)).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("failed"))
                .verifyComplete();

        verify(inventoryService).reserveStock(orderId, quantity);
        verify(inventoryService).releaseStock(orderId, quantity);
        verify(streamOperations, times(2)).add(eq("orders"), any(Map.class)); // OrderCreated + OrderFailed
        verify(meterRegistry).counter(eq("orders_failed"), any(String[].class));    }
}