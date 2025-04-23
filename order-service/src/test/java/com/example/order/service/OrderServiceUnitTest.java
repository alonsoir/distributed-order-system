package com.example.order.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderService}.
 * Tests cover order processing, creation, event publishing, and saga step execution,
 * including success, failure, and resilience scenarios.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    /**
     * Constants for error messages used in tests.
     */
    interface OrderServiceConstants {
        String ERROR_CIRCUIT_BREAKER = "Circuit breaker is open";
        String ERROR_TIMEOUT = "Operation timed out";
        String ERROR_INVALID_ORDER_ID = "OrderId must be positive";
        String ERROR_INVALID_QUANTITY = "Quantity must be positive";
        String ERROR_INVALID_AMOUNT = "Amount must be non-negative";
        String ERROR_NULL_CORRELATION_ID = "CorrelationId cannot be null or empty";
        String ERROR_NULL_EVENT_TYPE = "Event type cannot be null or empty";
        String ERROR_NULL_STEP_NAME = "Step name cannot be null";
        String ERROR_NULL_STEP_ACTION = "Action cannot be null";
    }

    @Mock private DatabaseClient databaseClient;
    @Mock private DatabaseClient.GenericExecuteSpec executeSpec;
    @Mock private DatabaseClient.GenericExecuteSpec bindSpec;
    @Mock private ReactiveRedisTemplate<String, Object> redisTemplate;
    @Mock private ReactiveStreamOperations<String, Object, Object> streamOps;
    @Mock private InventoryService inventoryService;
    @Mock private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock private CircuitBreaker circuitBreaker;
    @Mock private MeterRegistry meterRegistry;
    @Mock private Counter ordersSuccessCounter;
    @Mock private Counter redisSuccessCounter;
    @Mock private Counter redisFailureCounter;
    @Mock private Counter redisRetryCounter;
    @Mock private Counter outboxSuccessCounter;
    @Mock private Counter outboxFailureCounter;
    @Mock private Counter outboxRetryCounter;
    @Mock private Counter sagaStepSuccessCounter;
    @Mock private Counter sagaStepFailedCounter;
    @Mock private Counter sagaCompensationRetryCounter;
    @Mock private Timer redisTimer;
    @Mock private Timer outboxTimer;
    @Mock private Timer.Sample timerSample;
    @Mock private TransactionalOperator transactionalOperator;

    @InjectMocks private OrderService orderService;

    private AutoCloseable closeable;

    /**
     * Sets up mocks and common configurations before each test.
     */
    @BeforeEach
    void setUp() {
        Hooks.onOperatorDebug();

        closeable = Mockito.mockStatic(Timer.class);
        when(Timer.start()).thenReturn(timerSample);
        when(meterRegistry.counter("orders_success")).thenReturn(ordersSuccessCounter);
        when(meterRegistry.counter("event.publish.redis.success")).thenReturn(redisSuccessCounter);
        when(meterRegistry.counter("event.publish.redis.failure")).thenReturn(redisFailureCounter);
        when(meterRegistry.counter("event.publish.redis.retry")).thenReturn(redisRetryCounter);
        when(meterRegistry.counter("event.publish.outbox.success")).thenReturn(outboxSuccessCounter);
        when(meterRegistry.counter("event.publish.outbox.failure")).thenReturn(outboxFailureCounter);
        when(meterRegistry.counter(eq("saga_step_success"), anyString(), anyString())).thenReturn(sagaStepSuccessCounter);
        when(meterRegistry.counter(eq("saga_step_failed"), anyString(), anyString())).thenReturn(sagaStepFailedCounter);
        when(meterRegistry.counter("saga_compensation_retry")).thenReturn(sagaCompensationRetryCounter);
        when(meterRegistry.timer("event.publish.redis.timer")).thenReturn(redisTimer);
        when(meterRegistry.timer("event.publish.outbox.timer")).thenReturn(outboxTimer);

        when(circuitBreakerRegistry.circuitBreaker(anyString())).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.empty());

        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));

        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        // Remove default streamOps.add mock to avoid interference
    }

    /**
     * Cleans up static mocks after each test.
     */
    @AfterEach
    void tearDown() throws Exception {
        closeable.close();
    }

    // --- Order Processing Tests ---

    /**
     * Verifies that processOrder persists the order correctly in the database.
     */
    @Test
    void shouldPersistOrderSuccessfully() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(databaseClient.sql(sqlCaptor.capture())).thenReturn(executeSpec);

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order -> order.id().equals(orderId) && order.status().equals("completed"))
                .verifyComplete();

        assertTrue(sqlCaptor.getAllValues().contains("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :status, :correlationId)"));
        assertTrue(sqlCaptor.getAllValues().contains("UPDATE orders SET status = :status WHERE id = :id"));

        ArgumentCaptor<String> bindKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bindValueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(executeSpec, atLeastOnce()).bind(bindKeyCaptor.capture(), bindValueCaptor.capture());
        assertTrue(bindKeyCaptor.getAllValues().contains("status"));
        assertTrue(bindValueCaptor.getAllValues().contains("completed"));
        assertTrue(bindKeyCaptor.getAllValues().contains("id"));
        assertTrue(bindValueCaptor.getAllValues().contains(orderId));
    }

    /**
     * Verifies that processOrder publishes correct events to Redis.
     */
    @Test
    void shouldPublishEventsDuringOrderProcessing() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps, times(2)).add(eq("orders"), eventMapCaptor.capture());
        assertEquals("StockReserved", eventMapCaptor.getAllValues().get(1).get("type"));
        assertEquals(orderId, eventMapCaptor.getAllValues().get(1).get("orderId"));
    }

    /**
     * Verifies that processOrder updates metrics correctly.
     */
    @Test
    void shouldUpdateMetricsDuringOrderProcessing() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        verify(ordersSuccessCounter).increment();
        verify(redisSuccessCounter, times(2)).increment();
        verify(sagaStepSuccessCounter).increment();
        verify(timerSample, times(2)).stop(redisTimer);
    }

    /**
     * Verifies that processOrder handles an open circuit breaker.
     */
    @Test
    void shouldHandleCircuitBreakerOpen() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(false);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("failed") &&
                                order.correlationId().equals("unknown"))
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        assertEquals("OrderFailed", eventMapCaptor.getValue().get("type"));
        assertTrue(((String) eventMapCaptor.getValue().get("payload")).contains(OrderServiceConstants.ERROR_CIRCUIT_BREAKER));

        verify(inventoryService, never()).reserveStock(anyLong(), anyInt());
    }

    /**
     * Verifies that processOrder handles a half-open circuit breaker.
     */
    @Test
    void shouldHandleCircuitBreakerHalfOpen() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.HALF_OPEN);
        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order -> order.id().equals(orderId) && order.status().equals("completed"))
                .verifyComplete();

        verify(inventoryService).reserveStock(orderId, quantity);
        verify(ordersSuccessCounter).increment();
    }

    /**
     * Verifies that processOrder handles invalid inputs.
     */
    @ParameterizedTest
    @MethodSource("provideInvalidOrderInputs")
    void shouldFailToProcessOrderWithInvalidInputs(Long orderId, int quantity, double amount, String expectedError) {
        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException &&
                                throwable.getMessage().contains(expectedError))
                .verify();

        verify(databaseClient, never()).sql(anyString());
        verify(inventoryService, never()).reserveStock(anyLong(), anyInt());
    }

    private static Stream<Arguments> provideInvalidOrderInputs() {
        return Stream.of(
                Arguments.of(0L, 10, 100.0, OrderServiceConstants.ERROR_INVALID_ORDER_ID),
                Arguments.of(1L, 0, 100.0, OrderServiceConstants.ERROR_INVALID_QUANTITY),
                Arguments.of(1L, 10, -1.0, OrderServiceConstants.ERROR_INVALID_AMOUNT)
        );
    }

    /**
     * Verifies that processOrder handles a timeout.
     */
    @Test
    void shouldHandleTimeoutDuringOrderProcessing() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.error(new RuntimeException("Timeout")));

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("failed") &&
                                order.correlationId().equals("unknown"))
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        assertEquals("OrderFailed", eventMapCaptor.getValue().get("type"));
        assertTrue(((String) eventMapCaptor.getValue().get("payload")).contains(OrderServiceConstants.ERROR_TIMEOUT));
    }

    // --- Order Creation Tests ---

    /**
     * Verifies that createOrder persists the order correctly.
     */
    @Test
    void shouldPersistOrderDuringCreation() {
        Long orderId = 1L;
        String correlationId = "test-correlation-id";

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(databaseClient.sql(sqlCaptor.capture())).thenReturn(executeSpec);

        Mono<Order> result = orderService.createOrder(orderId, correlationId);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("pending") &&
                                order.correlationId().equals(correlationId))
                .verifyComplete();

        assertTrue(sqlCaptor.getAllValues().contains("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :status, :correlationId)"));
    }

    /**
     * Verifies that createOrder publishes an OrderCreated event.
     */
    @Test
    void shouldPublishOrderCreatedEvent() {
        Long orderId = 1L;
        String correlationId = "test-correlation-id";

        Mono<Order> result = orderService.createOrder(orderId, correlationId);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        assertEquals("OrderCreated", eventMapCaptor.getValue().get("type"));
        assertEquals(orderId, eventMapCaptor.getValue().get("orderId"));
        assertEquals(correlationId, eventMapCaptor.getValue().get("correlationId"));
    }

    /**
     * Verifies that createOrder handles a database failure.
     */
    @Test
    void shouldHandleDatabaseFailureDuringOrderCreation() {
        Long orderId = 1L;
        String correlationId = "test-correlation-id";
        String errorMessage = "Database error";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.error(new RuntimeException(errorMessage)));

        Mono<Order> result = orderService.createOrder(orderId, correlationId);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("failed") &&
                                order.correlationId().equals("unknown"))
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        assertEquals("OrderFailed", eventMapCaptor.getValue().get("type"));
        assertTrue(((String) eventMapCaptor.getValue().get("payload")).contains(errorMessage));
    }

    /**
     * Verifies that createOrder validates correlationId.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void shouldFailToCreateOrderWithInvalidCorrelationId(String correlationId) {
        Long orderId = 1L;

        Mono<Order> result = orderService.createOrder(orderId, correlationId);

        StepVerifier.create(result)
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException &&
                                throwable.getMessage().contains(OrderServiceConstants.ERROR_NULL_CORRELATION_ID))
                .verify();

        verify(databaseClient, never()).sql(anyString());
    }

    // --- Event Publishing Tests ---

    /**
     * Verifies that publishEvent publishes an OrderCreated event correctly.
     */
    @Test
    void shouldPublishOrderCreatedEventSuccessfully() {
        OrderService.OrderCreatedEvent event = new OrderService.OrderCreatedEvent(1L, "corr-id", "event-id", "pending");

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        assertEquals("OrderCreated", eventMapCaptor.getValue().get("type"));
        assertEquals(1L, eventMapCaptor.getValue().get("orderId"));
        assertEquals("corr-id", eventMapCaptor.getValue().get("correlationId"));
    }

    /**
     * Verifies that publishEvent publishes a StockReserved event correctly.
     */
    @Test
    void shouldPublishStockReservedEventSuccessfully() {
        OrderService.StockReservedEvent event = new OrderService.StockReservedEvent(1L, "corr-id", "event-id", 10);

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        assertEquals("StockReserved", eventMapCaptor.getValue().get("type"));
        assertEquals(1L, eventMapCaptor.getValue().get("orderId"));
        assertEquals(10, eventMapCaptor.getValue().get("quantity"));
    }

    @Test
    void shouldHandlePersistentOutboxFailure() {
        OrderService.OrderCreatedEvent event = new OrderService.OrderCreatedEvent(1L, "corr-id", "event-id", "pending");
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.error(new RuntimeException("Redis connection failed")));
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.error(new RuntimeException("Database failure")));
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("redisEventPublishing");
        when(circuitBreakerRegistry.circuitBreaker("redisEventPublishing")).thenReturn(circuitBreaker);

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyErrorMatches(e -> e.getMessage().contains("Outbox persist retry exhausted"));

        verify(streamOps, times(4)).add(eq("orders"), anyMap());
        verify(redisFailureCounter, times(4)).increment();
        verify(redisRetryCounter, times(3)).increment();
        verify(outboxFailureCounter, times(4)).increment(); // Once per attempt
        verify(outboxRetryCounter, times(3)).increment(); // Once per retry
        verify(outboxSuccessCounter, never()).increment();
    }

    @Test
    void shouldNotRetryOnIllegalArgumentException() {
        OrderService.OrderCreatedEvent event = mock(OrderService.OrderCreatedEvent.class);
        when(event.getType()).thenReturn(null); // Triggers IllegalArgumentException in buildEventMap
        when(event.getOrderId()).thenReturn(1L);

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyError(IllegalArgumentException.class);

        verify(streamOps, never()).add(anyString(), anyMap());
        verify(redisRetryCounter, never()).increment();
        verify(databaseClient, never()).sql(anyString());
    }

    @Test
    void shouldPublishToOutboxWhenCircuitBreakerOpens() {
        OrderService.OrderCreatedEvent event = new OrderService.OrderCreatedEvent(1L, "corr-id", "event-id", "pending");
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreakerRegistry.circuitBreaker("redisEventPublishing")).thenReturn(circuitBreaker);
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.error(new RuntimeException("Redis connection failed")));
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());
        when(circuitBreaker.run(any(), any())).thenThrow(new CallNotPermittedException(circuitBreaker));

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        verify(streamOps, never()).add(anyString(), anyMap()); // No calls due to circuit breaker
        verify(redisFailureCounter, never()).increment();
        verify(redisRetryCounter, never()).increment();
        verify(outboxSuccessCounter).increment();
        verify(databaseClient).sql(eq("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)"));
    }

    /**
     * Verifies that publishEvent falls back to outbox on Redis failure.
     */
    @Test
    void shouldPublishToOutboxOnRedisFailure() {
        // Setup Logback appender for testing
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(OrderService.class);
        logger.addAppender(listAppender);

        OrderService.OrderCreatedEvent event = new OrderService.OrderCreatedEvent(1L, "corr-id", "event-id", "pending");
        String errorMessage = "Redis connection failed";

        // Reset streamOps to avoid default mock interference
        reset(streamOps);
        // Mock streamOps.add to fail consistently
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.error(new RuntimeException(errorMessage)));
        // Mock databaseClient.sql to succeed
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        // Mock executeSpec with bindings for outbox insertion
        when(executeSpec.bind(eq("event_type"), anyString())).thenReturn(bindSpec);
        when(executeSpec.bind(eq("correlationId"), anyString())).thenReturn(bindSpec);
        when(executeSpec.bind(eq("eventId"), anyString())).thenReturn(bindSpec);
        when(executeSpec.bind(eq("payload"), anyString())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.empty());
        // Mock circuit breaker to ensure retries are not bypassed
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("redisEventPublishing");
        when(circuitBreakerRegistry.circuitBreaker("redisEventPublishing")).thenReturn(circuitBreaker);

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        verify(streamOps, times(4)).add(eq("orders"), anyMap()); // 1 initial + 3 retries
        verify(redisFailureCounter, times(4)).increment(); // Once per failed attempt
        verify(redisRetryCounter, times(3)).increment(); // Once per retry
        verify(redisSuccessCounter, never()).increment(); // No successful Redis publish
        verify(outboxSuccessCounter).increment(); // Outbox insertion succeeds
        verify(outboxFailureCounter, never()).increment(); // No outbox failures
        verify(outboxRetryCounter, never()).increment(); // No outbox retries

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(databaseClient).sql(sqlCaptor.capture());
        assertEquals("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)", sqlCaptor.getValue());

        // Verify logs
        List<ILoggingEvent> logs = listAppender.list;
        assertEquals(9, logs.size()); // 4 error + 3 retry + 1 circuit breaker + 1 outbox success
        assertEquals(4, logs.stream().filter(log -> log.getFormattedMessage().contains("Failed to publish event")).count());
        assertEquals(3, logs.stream().filter(log -> log.getFormattedMessage().contains("Retrying Redis publish")).count());
        assertEquals(1, logs.stream().filter(log -> log.getFormattedMessage().contains("Redis circuit breaker tripped")).count());
        assertEquals(1, logs.stream().filter(log -> log.getFormattedMessage().contains("Persisted event")).count());
    }

    @Test
    void shouldLogCriticalErrorOnPersistentOutboxFailure() {
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(OrderService.class);
        logger.addAppender(listAppender);

        OrderService.OrderCreatedEvent event = new OrderService.OrderCreatedEvent(1L, "corr-id", "event-id", "pending");
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.error(new RuntimeException("Redis failure")));
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.error(new RuntimeException("Database failure")));
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("redisEventPublishing");
        when(circuitBreakerRegistry.circuitBreaker("redisEventPublishing")).thenReturn(circuitBreaker);

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyErrorMatches(e -> e.getMessage().contains("Outbox persist retry exhausted"));

        verify(streamOps, times(4)).add(eq("orders"), anyMap());
        verify(redisFailureCounter, times(4)).increment();
        verify(redisRetryCounter, times(3)).increment();
        verify(outboxFailureCounter, times(4)).increment();
        verify(outboxRetryCounter, times(3)).increment();
        verify(outboxSuccessCounter, never()).increment();

        List<ILoggingEvent> logs = listAppender.list;
        assertTrue(logs.stream().anyMatch(log -> log.getFormattedMessage().contains("Failed to persist event")));
    }

    @Test
    void shouldPublishToRedisAfterOneRetry() {
        OrderService.OrderCreatedEvent event = new OrderService.OrderCreatedEvent(1L, "corr-id", "event-id", "pending");

        // Mock streamOps.add to fail on first attempt and succeed on second
        when(streamOps.add(anyString(), anyMap()))
                .thenReturn(Mono.error(new RuntimeException("Redis connection failed")))
                .thenReturn(Mono.just(RecordId.of("record-id"))); // Return Mono<RecordId>

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        verify(streamOps, times(2)).add(eq("orders"), anyMap()); // 1 initial + 1 retry
        verify(redisSuccessCounter).increment();
        verify(redisFailureCounter).increment();
        verify(redisRetryCounter).increment();
        verify(databaseClient, never()).sql(anyString());
        verify(outboxSuccessCounter, never()).increment();
    }

    /**
     * Verifies that publishEvent handles concurrent Redis and outbox failure.
     */
    @Test
    void shouldHandleConcurrentRedisAndOutboxFailure() {
        OrderService.OrderCreatedEvent event = new OrderService.OrderCreatedEvent(1L, "corr-id", "event-id", "pending");
        String redisError = "Redis connection failed";
        String outboxError = "Outbox insert failed";

        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.error(new RuntimeException(redisError)));
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.error(new RuntimeException(outboxError)));

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable.getMessage().contains(outboxError))
                .verify();

        verify(redisFailureCounter).increment();
        verify(outboxFailureCounter).increment();
    }

    /**
     * Verifies that publishEvent validates event fields.
     */
    @ParameterizedTest
    @MethodSource("provideInvalidEventData")
    void shouldFailToPublishEventWithInvalidData(String correlationId, String eventType, String expectedError) {
        OrderService.OrderEvent event = new OrderService.OrderEvent() {
            @Override
            public Long getOrderId() { return 1L; }
            @Override
            public String getCorrelationId() { return correlationId; }
            @Override
            public String getEventId() { return "event-id"; }
            @Override
            public String getType() { return eventType; }
            @Override
            public String toJson() { return "{}"; }
        };

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result)
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException &&
                                throwable.getMessage().contains(expectedError))
                .verify();

        verify(streamOps, never()).add(anyString(), anyMap());
    }

    private static Stream<Arguments> provideInvalidEventData() {
        return Stream.of(
                Arguments.of(null, "OrderCreated", OrderServiceConstants.ERROR_NULL_CORRELATION_ID),
                Arguments.of("", "OrderCreated", OrderServiceConstants.ERROR_NULL_CORRELATION_ID),
                Arguments.of("corr-id", null, OrderServiceConstants.ERROR_NULL_EVENT_TYPE),
                Arguments.of("corr-id", "", OrderServiceConstants.ERROR_NULL_EVENT_TYPE)
        );
    }

    // --- Saga Step Execution Tests ---

    /**
     * Verifies that executeStep completes successfully.
     */
    @Test
    void shouldExecuteSagaStepSuccessfully() {
        Long orderId = 1L;
        String correlationId = "corr-id";
        String eventId = UUID.randomUUID().toString();
        int quantity = 10;

        OrderService.SagaStep step = createSagaStep(orderId, correlationId, eventId, quantity);
        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        Mono<OrderService.OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectNextMatches(event ->
                        event.getType().equals("StockReserved") &&
                                event.getOrderId().equals(orderId))
                .verifyComplete();

        verify(sagaStepSuccessCounter).increment();
        verify(inventoryService).reserveStock(orderId, quantity);
    }

    /**
     * Verifies that executeStep handles action failure and triggers compensation.
     */
    @Test
    void shouldHandleSagaStepActionFailure() {
        Long orderId = 1L;
        String correlationId = "corr-id";
        String eventId = UUID.randomUUID().toString();
        int quantity = 10;
        String errorMessage = "Insufficient stock";

        OrderService.SagaStep step = createSagaStep(orderId, correlationId, eventId, quantity);
        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.error(new RuntimeException(errorMessage)));
        when(inventoryService.releaseStock(orderId, quantity)).thenReturn(Mono.empty());

        Mono<OrderService.OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable.getMessage().contains(errorMessage))
                .verify();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        assertEquals("OrderFailed", eventMapCaptor.getValue().get("type"));

        verify(sagaStepFailedCounter).increment();
        verify(inventoryService).releaseStock(orderId, quantity);
    }

    /**
     * Verifies that executeStep handles compensation success after retries.
     */
    @Test
    void shouldHandleSagaStepCompensationSuccessAfterRetries() {
        Long orderId = 1L;
        String correlationId = "corr-id";
        String eventId = UUID.randomUUID().toString();
        int quantity = 10;
        String actionError = "Insufficient stock";

        OrderService.SagaStep step = createSagaStep(orderId, correlationId, eventId, quantity);
        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.error(new RuntimeException(actionError)));
        when(inventoryService.releaseStock(orderId, quantity))
                .thenReturn(Mono.error(new RuntimeException("First retry failed")))
                .thenReturn(Mono.empty());

        Mono<OrderService.OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable.getMessage().contains(actionError))
                .verify();

        verify(inventoryService, times(2)).releaseStock(orderId, quantity);
        verify(sagaCompensationRetryCounter).increment();
    }

    /**
     * Verifies that executeStep handles persistent compensation failure.
     */
    @Test
    void shouldHandleSagaStepPersistentCompensationFailure() {
        Long orderId = 1L;
        String correlationId = "corr-id";
        String eventId = UUID.randomUUID().toString();
        int quantity = 10;
        String actionError = "Insufficient stock";
        String compensationError = "Compensation failed";

        OrderService.SagaStep step = createSagaStep(orderId, correlationId, eventId, quantity);
        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.error(new RuntimeException(actionError)));
        when(inventoryService.releaseStock(orderId, quantity))
                .thenReturn(Mono.error(new RuntimeException(compensationError)))
                .thenReturn(Mono.error(new RuntimeException(compensationError)))
                .thenReturn(Mono.error(new RuntimeException(compensationError)));
        when(redisTemplate.opsForList().leftPush(eq("failed-compensations"), any())).thenReturn(Mono.just(1L));

        Mono<OrderService.OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable.getMessage().contains(actionError))
                .verify();

        ArgumentCaptor<OrderService.CompensationTask> taskCaptor = ArgumentCaptor.forClass(OrderService.CompensationTask.class);
        verify(redisTemplate.opsForList()).leftPush(eq("failed-compensations"), taskCaptor.capture());
        assertEquals(compensationError, taskCaptor.getValue().error());

        verify(inventoryService, times(3)).releaseStock(orderId, quantity);
    }

    /**
     * Verifies that SagaStep validates required fields.
     */
    @ParameterizedTest
    @MethodSource("provideInvalidSagaStepData")
    void shouldFailToCreateSagaStepWithInvalidFields(String name, Supplier<Mono<?>> action, String expectedError) {
        assertThrows(NullPointerException.class, () -> OrderService.SagaStep.builder()
                .name(name)
                .action(action)
                .compensation(() -> Mono.empty())
                .successEvent(eid -> new OrderService.StockReservedEvent(1L, "corr-id", eid, 10))
                .orderId(1L)
                .correlationId("corr-id")
                .eventId("event-id")
                .build(), expectedError);
    }

    private static Stream<Arguments> provideInvalidSagaStepData() {
        return Stream.of(
                Arguments.of(null, (Supplier<Mono<?>>) () -> Mono.empty(), OrderServiceConstants.ERROR_NULL_STEP_NAME),
                Arguments.of("reserveStock", null, OrderServiceConstants.ERROR_NULL_STEP_ACTION)
        );
    }

    /**
     * Helper method to create a SagaStep for tests.
     */
    private OrderService.SagaStep createSagaStep(Long orderId, String correlationId, String eventId, int quantity) {
        return OrderService.SagaStep.builder()
                .name("reserveStock")
                .action(() -> inventoryService.reserveStock(orderId, quantity))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                .successEvent(eid -> new OrderService.StockReservedEvent(orderId, correlationId, eid, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();
    }

    /**
     * Verifies that metrics are not updated if MeterRegistry fails.
     */
    @Test
    void shouldHandleMeterRegistryFailure() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());
        when(meterRegistry.counter("orders_success")).thenThrow(new RuntimeException("Metrics failure"));

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order -> order.id().equals(orderId) && order.status().equals("completed"))
                .verifyComplete();

        verify(inventoryService).reserveStock(orderId, quantity);
        // Metrics failure should not prevent order processing
    }
    @Test
    void shouldRetryOnIllegalArgumentException() {
        OrderService.OrderCreatedEvent event = mock(OrderService.OrderCreatedEvent.class);
        when(event.getType()).thenReturn(null); // Triggers IllegalArgumentException
        when(event.getOrderId()).thenReturn(1L);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.empty());
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("redisEventPublishing");
        when(circuitBreakerRegistry.circuitBreaker("redisEventPublishing")).thenReturn(circuitBreaker);

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        verify(streamOps, times(4)).add(eq("orders"), anyMap());
        verify(redisFailureCounter, times(4)).increment();
        verify(redisRetryCounter, times(3)).increment();
        verify(outboxSuccessCounter).increment();
    }

    @Test
    void shouldIncrementOutboxFailureCounterPerRetry() {
        OrderService.OrderCreatedEvent event = new OrderService.OrderCreatedEvent(1L, "corr-id", "event-id", "pending");
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.error(new RuntimeException("Redis failure")));
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.error(new RuntimeException("Database failure")));
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("redisEventPublishing");
        when(circuitBreakerRegistry.circuitBreaker("redisEventPublishing")).thenReturn(circuitBreaker);

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyError();

        verify(outboxFailureCounter, times(4)).increment(); // Once per attempt
        verify(outboxRetryCounter, times(3)).increment();
    }
    @Test
    void shouldPushCorrectCompensationTaskOnFailure() {
        Long orderId = 1L;
        String correlationId = "corr-id";
        String eventId = UUID.randomUUID().toString();
        int quantity = 10;
        String error = "Compensation failed";

        OrderService.SagaStep step = createSagaStep(orderId, correlationId, eventId, quantity);
        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.error(new RuntimeException("Action failed")));
        when(inventoryService.releaseStock(orderId, quantity)).thenReturn(Mono.error(new RuntimeException(error)));
        when(redisTemplate.opsForList().leftPush(eq("failed-compensations"), any())).thenReturn(Mono.just(1L));

        Mono<OrderService.OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result).verifyError();

        ArgumentCaptor<OrderService.CompensationTask> taskCaptor = ArgumentCaptor.forClass(OrderService.CompensationTask.class);
        verify(redisTemplate.opsForList()).leftPush(eq("failed-compensations"), taskCaptor.capture());
        OrderService.CompensationTask task = taskCaptor.getValue();
        assertEquals(orderId, task.orderId());
        assertEquals(correlationId, task.correlationId());
        assertEquals(error, task.error());
        assertEquals(0, task.retries());
    }

}