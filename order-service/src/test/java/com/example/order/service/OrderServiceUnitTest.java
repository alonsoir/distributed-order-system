package com.example.order.service;

import com.example.order.events.EventTopics;
import com.example.order.domain.Order;
import com.example.order.events.*;
import com.example.order.model.SagaStep;
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
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;
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

    @Mock
    private DatabaseClient databaseClient;
    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;
    @Mock
    private DatabaseClient.GenericExecuteSpec bindSpec;
    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;
    @Mock
    private ReactiveStreamOperations<String, Object, Object> streamOps;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock
    private CircuitBreaker circuitBreaker;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private Counter ordersSuccessCounter;
    @Mock
    private Counter redisSuccessCounter;
    @Mock
    private Counter redisFailureCounter;
    @Mock
    private Counter redisRetryCounter;
    @Mock
    private Counter outboxSuccessCounter;
    @Mock
    private Counter outboxFailureCounter;
    @Mock
    private Counter outboxRetryCounter;
    @Mock
    private Counter sagaStepSuccessCounter;
    @Mock
    private Counter sagaStepFailedCounter;
    @Mock
    private Counter sagaCompensationRetryCounter;
    @Mock
    private Timer redisTimer;
    @Mock
    private Timer outboxTimer;
    @Mock
    private Timer sagaTimer;
    @Mock
    private Timer.Sample timerSample;
    @Mock
    private TransactionalOperator transactionalOperator;
    @Mock
    private SagaOrchestrator sagaOrchestrator;

    @InjectMocks
    private OrderServiceImpl orderService;

    private AutoCloseable closeable;

    /**
     * Sets up mocks and common configurations before each test.
     */
    @SuppressWarnings("unchecked")
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
        when(meterRegistry.counter("event.publish.outbox.retry")).thenReturn(outboxRetryCounter);
        when(meterRegistry.counter(eq("saga_step_success"), anyString(), anyString())).thenReturn(sagaStepSuccessCounter);
        when(meterRegistry.counter(eq("saga_step_failed"), anyString(), anyString())).thenReturn(sagaStepFailedCounter);
        when(meterRegistry.counter("saga_compensation_retry")).thenReturn(sagaCompensationRetryCounter);
        when(meterRegistry.timer("event.publish.redis.timer")).thenReturn(redisTimer);
        when(meterRegistry.timer("event.publish.outbox.timer")).thenReturn(outboxTimer);
        when(meterRegistry.timer("saga_step.timer")).thenReturn(sagaTimer);

        when(circuitBreakerRegistry.circuitBreaker(anyString())).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.empty());

        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));

        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        // No default streamOps.add mock to avoid interference
    }

    /**
     * Cleans up static mocks after each test.
     */
    @AfterEach
    void tearDown() throws Exception {
        closeable.close();
    }

    // --- Order Processing Tests ---

    @Test
    void shouldExecuteCompensationOnStepFailure() {
        Long orderId = 1L;
        String correlationId = "corr-id";
        String eventId = UUID.randomUUID().toString();
        int quantity = 10;

        SagaStep step = createSagaStep(orderId, correlationId, eventId, quantity);
        when(inventoryService.reserveStock(orderId, quantity))
                .thenReturn(Mono.error(new RuntimeException("Stock reservation failed")));
        when(inventoryService.releaseStock(orderId, quantity)).thenReturn(Mono.empty());

        Mono<OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable.getMessage().contains("Stock reservation failed"))
                .verify();

        verify(inventoryService).reserveStock(orderId, quantity);
        verify(inventoryService).releaseStock(orderId, quantity); // Verify compensation
    }

    /**
     * Verifies that processOrder persists the order correctly in the database.
     */
    @Test
    void shouldPersistOrderSuccessfully() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;
        String correlationId = UUID.randomUUID().toString();
        Order order = new Order(orderId, "completed", correlationId);

        when(sagaOrchestrator.executeOrderSaga(orderId, quantity, amount, correlationId)).thenReturn(Mono.just(order));

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(o -> o.id().equals(orderId) && o.status().equals("completed"))
                .verifyComplete();

        verify(ordersSuccessCounter).increment();
    }

    /**
     * Verifies that processOrder publishes correct events to Redis.
     */
    @Test
    void shouldPublishEventsDuringOrderProcessing() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;
        String correlationId = UUID.randomUUID().toString();
        Order order = new Order(orderId, "completed", correlationId);

        when(sagaOrchestrator.executeOrderSaga(orderId, quantity, amount, correlationId)).thenReturn(Mono.just(order));
        when(sagaOrchestrator.publishFailedEvent(any())).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        verify(sagaOrchestrator).executeOrderSaga(orderId, quantity, amount, anyString());
    }

    /**
     * Verifies that processOrder updates metrics correctly.
     */
    @Test
    void shouldUpdateMetricsDuringOrderProcessing() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;
        String correlationId = UUID.randomUUID().toString();
        Order order = new Order(orderId, "completed", correlationId);

        when(sagaOrchestrator.executeOrderSaga(orderId, quantity, amount, correlationId)).thenReturn(Mono.just(order));

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        verify(ordersSuccessCounter).increment();
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
        when(sagaOrchestrator.publishFailedEvent(any())).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("failed") &&
                                order.correlationId().equals("unknown"))
                .verifyComplete();

        verify(sagaOrchestrator).publishFailedEvent(any(OrderFailedEvent.class));
    }

    /**
     * Verifies that processOrder handles a half-open circuit breaker.
     */
    @Test
    void shouldHandleCircuitBreakerHalfOpen() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;
        String correlationId = UUID.randomUUID().toString();
        Order order = new Order(orderId, "completed", correlationId);

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.HALF_OPEN);
        when(sagaOrchestrator.executeOrderSaga(orderId, quantity, amount, correlationId)).thenReturn(Mono.just(order));

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(o -> o.id().equals(orderId) && o.status().equals("completed"))
                .verifyComplete();

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

        verify(sagaOrchestrator, never()).executeOrderSaga(anyLong(), anyInt(), anyDouble(), anyString());
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
        String correlationId = UUID.randomUUID().toString();

        when(sagaOrchestrator.executeOrderSaga(orderId, quantity, amount, correlationId))
                .thenReturn(Mono.error(new RuntimeException("Timeout")));
        when(sagaOrchestrator.publishFailedEvent(any())).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("failed") &&
                                order.correlationId().equals("unknown"))
                .verifyComplete();

        verify(sagaOrchestrator).publishFailedEvent(any(OrderFailedEvent.class));
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
        when(streamOps.add(anyString(), anyMap())).thenReturn((Mono<RecordId>) Mono.just(RecordId.of("record-id")));

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

        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<Order> result = orderService.createOrder(orderId, correlationId);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        @SuppressWarnings("unchecked")
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
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

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

    /**
     * Verifies that createOrder validates orderId.
     */
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void shouldFailToCreateOrderWithInvalidOrderId(Long orderId) {
        String correlationId = "test-correlation-id";

        Mono<Order> result = orderService.createOrder(orderId, correlationId);

        StepVerifier.create(result)
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException &&
                                throwable.getMessage().contains(OrderServiceConstants.ERROR_INVALID_ORDER_ID))
                .verify();

        verify(databaseClient, never()).sql(anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldHandleRedisSuccessAfterRetries() {
        OrderEvent event = new OrderCreatedEvent(1L, "corr-id", "event-id", "pending");

        reset(streamOps);
        when(streamOps.add(anyString(), anyMap()))
                .thenReturn(Mono.error(new RuntimeException("Redis failure")))
                .thenReturn(Mono.error(new RuntimeException("Redis failure")))
                .thenReturn(Mono.just(RecordId.of("record-id")));
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("redisEventPublishing");
        when(circuitBreakerRegistry.circuitBreaker("redisEventPublishing")).thenReturn(circuitBreaker);

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        verify(streamOps, times(3)).add(eq("orders"), anyMap());
        verify(redisFailureCounter, times(2)).increment();
        verify(redisRetryCounter, times(2)).increment();
        verify(redisSuccessCounter).increment();
        verify(outboxSuccessCounter, never()).increment();
        verify(timerSample).stop(redisTimer);
    }

    @Test
    void shouldHandleMeterRegistryFailureDuringEventPublishing() {
        OrderEvent event = new OrderCreatedEvent(1L, "corr-id", "event-id", "pending");

        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));
        when(meterRegistry.counter("event.publish.redis.success")).thenThrow(new RuntimeException("Metrics failure"));

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        verify(streamOps).add(eq("orders"), anyMap());
        verify(timerSample).stop(redisTimer);
    }

    @Test
    void shouldHandleMeterRegistryFailureDuringSagaStep() {
        Long orderId = 1L;
        String correlationId = "corr-id";
        String eventId = UUID.randomUUID().toString();
        int quantity = 10;

        SagaStep step = createSagaStep(orderId, correlationId, eventId, quantity);
        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());
        when(meterRegistry.counter(eq("saga_step_success"), anyString(), anyString()))
                .thenThrow(new RuntimeException("Metrics failure"));
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectNextMatches(event ->
                        event.getType().equals(OrderEventType.STOCK_RESERVED) &&
                                event.getOrderId().equals(orderId))
                .verifyComplete();

        verify(inventoryService).reserveStock(orderId, quantity);
        verify(streamOps).add(eq("orders"), anyMap());
        verify(timerSample).stop(sagaTimer);
    }


    private SagaStep createSagaStep(Long orderId, String correlationId, String eventId, int quantity) {
        return SagaStep.builder()
                .name("reserveStock")
                .topic(EventTopics.STOCK_RESERVED.getTopic())
                .action(() -> inventoryService.reserveStock(orderId, quantity))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                .successEvent(payload -> new StockReservedEvent(orderId, correlationId, eventId, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();
    }

    @Test
    void shouldHandleConcurrentDatabaseAndRedisFailure() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.error(new RuntimeException("Database failure")));
        reset(streamOps);
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.error(new RuntimeException("Redis failure")));
        when(redisTemplate.opsForList().leftPush(eq("failed-outbox-events"), any())).thenReturn(Mono.just(1L));

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("failed") &&
                                order.correlationId().equals("unknown"))
                .verifyComplete();

        verify(redisTemplate.opsForList()).leftPush(eq("failed-outbox-events"), any());
        verify(redisFailureCounter, times(4)).increment();
        verify(redisRetryCounter, times(3)).increment();
    }

    @ParameterizedTest
    @MethodSource("provideValidOrderInputs")
    void shouldProcessOrderWithDifferentQuantitiesAndAmounts(Long orderId, int quantity, double amount) {
        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order -> order.id().equals(orderId) && order.status().equals("completed"))
                .verifyComplete();

        verify(inventoryService).reserveStock(orderId, quantity);
        verify(ordersSuccessCounter).increment();
        verify(streamOps, times(2)).add(eq("orders"), anyMap());
    }

    private static Stream<Arguments> provideValidOrderInputs() {
        return Stream.of(
                Arguments.of(1L, 5, 50.0),
                Arguments.of(2L, 10, 100.0),
                Arguments.of(3L, 20, 200.0)
        );
    }

    /**
     * Verifies that createOrder handles MeterRegistry failure.
     */
    @Test
    void shouldHandleMeterRegistryFailureDuringOrderCreation() {
        Long orderId = 1L;
        String correlationId = "test-correlation-id";

        when(meterRegistry.counter("orders_success")).thenThrow(new RuntimeException("Metrics failure"));
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<Order> result = orderService.createOrder(orderId, correlationId);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("pending") &&
                                order.correlationId().equals(correlationId))
                .verifyComplete();

        verify(databaseClient).sql(anyString());
    }

    // --- Event Publishing Tests ---

    /**
     * Verifies that publishEvent publishes an OrderCreated event correctly.
     */
    @Test
    void shouldPublishOrderCreatedEventSuccessfully() {
        OrderEvent event = new OrderCreatedEvent(1L, "corr-id", "event-id", "pending");

        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        Map<String, Object> capturedEvent = eventMapCaptor.getValue();
        assertEquals("OrderCreated", capturedEvent.get("type"));
        assertEquals(1L, capturedEvent.get("orderId"));
        assertEquals("corr-id", capturedEvent.get("correlationId"));
        assertEquals("event-id", capturedEvent.get("eventId"));
        assertTrue(capturedEvent.get("payload").toString().contains("pending"));

        verify(redisSuccessCounter).increment();
        verify(timerSample).stop(redisTimer);
        verify(redisFailureCounter, never()).increment();
        verify(redisRetryCounter, never()).increment();
    }
}