package com.example.order.service.unit;

import com.example.order.events.EventTopics;
import com.example.order.domain.Order;
import com.example.order.events.*;
import com.example.order.model.SagaStep;
import com.example.order.service.*;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
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
@ActiveProfiles("unit")
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

    // Constantes para trazabilidad
    private static final Long TEST_ORDER_ID = 123456L;
    private static final String TEST_CORRELATION_ID = "test-correlation-id-fixed";
    private static final String TEST_EVENT_ID = "test-event-id-fixed";;
    private static final int TEST_QUANTITY = 10;
    private static final double TEST_AMOUNT = 100.0;

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

    @Mock
    private IdGenerator idGenerator;

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
        SagaStep step = createSagaStep(TEST_ORDER_ID, TEST_CORRELATION_ID, TEST_EVENT_ID, TEST_QUANTITY);
        when(inventoryService.reserveStock(TEST_ORDER_ID, TEST_QUANTITY))
                .thenReturn(Mono.error(new RuntimeException("Stock reservation failed")));
        when(inventoryService.releaseStock(TEST_ORDER_ID, TEST_QUANTITY)).thenReturn(Mono.empty());

        Mono<OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable.getMessage().contains("Stock reservation failed"))
                .verify();

        verify(inventoryService).reserveStock(TEST_ORDER_ID, TEST_QUANTITY);
        verify(inventoryService).releaseStock(TEST_ORDER_ID, TEST_QUANTITY); // Verify compensation
    }

    /**
     * Verifies that processOrder persists the order correctly in the database.
     */
    @Test
    void shouldPersistOrderSuccessfully() {
        String externalReference = idGenerator.generateExternalReference();
        // esto no puede funcionar en la vida porque el orderId se genera en el servicio. No se debe generar en el
        // controller.
        Order order = new Order(TEST_ORDER_ID, "completed", TEST_CORRELATION_ID);

        when(sagaOrchestrator.executeOrderSaga(TEST_ORDER_ID, TEST_QUANTITY, TEST_EVENT_ID, TEST_AMOUNT, TEST_CORRELATION_ID)).thenReturn(Mono.just(order));

        Mono<Order> result = orderService.processOrder(externalReference, TEST_QUANTITY, TEST_AMOUNT);

        StepVerifier.create(result)
                .expectNextMatches(o -> o.id().equals(TEST_ORDER_ID) && o.status().equals("completed"))
                .verifyComplete();

        verify(ordersSuccessCounter).increment();
    }

    /**
     * Verifies that processOrder publishes correct events to Redis.
     */
    @Test
    void shouldPublishEventsDuringOrderProcessing() {
        String externalReference = idGenerator.generateExternalReference();
        Order order = new Order(TEST_ORDER_ID, "completed", TEST_CORRELATION_ID);
        // Mono<Order> executeOrderSaga(Long orderId, int quantity, String eventId, double amount, String correlationId)
        when(sagaOrchestrator.executeOrderSaga(eq(TEST_ORDER_ID), eq(TEST_QUANTITY), anyString(), eq(TEST_AMOUNT), eq(TEST_CORRELATION_ID)))
                .thenReturn(Mono.just(order));
        when(sagaOrchestrator.publishFailedEvent(any())).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(externalReference, TEST_QUANTITY, TEST_AMOUNT);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        // Verificamos que se llamó al método executeOrderSaga con los parámetros correctos
        // Usamos anyLong() para el eventId porque podría generarse en el método processOrder
        verify(sagaOrchestrator).executeOrderSaga(eq(TEST_ORDER_ID), eq(TEST_QUANTITY), anyString(),
                eq(TEST_AMOUNT), anyString());
    }

    /**
     * Verifies that processOrder updates metrics correctly.
     */
    @Test
    void shouldUpdateMetricsDuringOrderProcessing() {
        String externalReference = idGenerator.generateExternalReference();
        // Estan todos mal, el objeto order lo devuelve el sagaOrchestrator, con el orderId que se genera en el
        // processOrder, pero viendo el código, el orderId se debería generar en el sagaOrchestrator.
        Order order = new Order(TEST_ORDER_ID, "completed", TEST_CORRELATION_ID);

        when(sagaOrchestrator.executeOrderSaga(eq(TEST_ORDER_ID), eq(TEST_QUANTITY), anyLong(), eq(TEST_AMOUNT), anyString()))
                .thenReturn(Mono.just(order));

        Mono<Order> result = orderService.processOrder(TEST_ORDER_ID, TEST_QUANTITY, TEST_AMOUNT);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        verify(ordersSuccessCounter).increment();
    }

    /**
     * Verifies that processOrder handles an open circuit breaker.
     */
    @Test
    void shouldHandleCircuitBreakerOpen() {
        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(false);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);
        when(sagaOrchestrator.publishFailedEvent(any())).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(TEST_ORDER_ID, TEST_QUANTITY, TEST_AMOUNT);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(TEST_ORDER_ID) &&
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
        Order order = new Order(TEST_ORDER_ID, "completed", TEST_CORRELATION_ID);

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.HALF_OPEN);
        when(sagaOrchestrator.executeOrderSaga(eq(TEST_ORDER_ID), eq(TEST_QUANTITY), anyLong(), eq(TEST_AMOUNT), anyString()))
                .thenReturn(Mono.just(order));

        Mono<Order> result = orderService.processOrder(TEST_ORDER_ID, TEST_QUANTITY, TEST_AMOUNT);

        StepVerifier.create(result)
                .expectNextMatches(o -> o.id().equals(TEST_ORDER_ID) && o.status().equals("completed"))
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

        verify(sagaOrchestrator, never()).executeOrderSaga(anyLong(), anyInt(), anyLong(), anyDouble(), anyString());
    }

    private static Stream<Arguments> provideInvalidOrderInputs() {
        return Stream.of(
                Arguments.of(0L, TEST_QUANTITY, TEST_AMOUNT, OrderServiceConstants.ERROR_INVALID_ORDER_ID),
                Arguments.of(TEST_ORDER_ID, 0, TEST_AMOUNT, OrderServiceConstants.ERROR_INVALID_QUANTITY),
                Arguments.of(TEST_ORDER_ID, TEST_QUANTITY, -1.0, OrderServiceConstants.ERROR_INVALID_AMOUNT)
        );
    }

    /**
     * Verifies that processOrder handles a timeout.
     */
    @Test
    void shouldHandleTimeoutDuringOrderProcessing() {
        when(sagaOrchestrator.executeOrderSaga(eq(TEST_ORDER_ID), eq(TEST_QUANTITY), anyLong(), eq(TEST_AMOUNT), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Timeout")));
        when(sagaOrchestrator.publishFailedEvent(any())).thenReturn(Mono.empty());

        Mono<Order> result = orderService.processOrder(TEST_ORDER_ID, TEST_QUANTITY, TEST_AMOUNT);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(TEST_ORDER_ID) &&
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
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(databaseClient.sql(sqlCaptor.capture())).thenReturn(executeSpec);
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<Order> result = orderService.createOrder(TEST_ORDER_ID, TEST_CORRELATION_ID);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(TEST_ORDER_ID) &&
                                order.status().equals("pending") &&
                                order.correlationId().equals(TEST_CORRELATION_ID))
                .verifyComplete();

        assertTrue(sqlCaptor.getAllValues().contains("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :status, :correlationId)"));
    }

    /**
     * Verifies that createOrder publishes an OrderCreated event.
     */
    @Test
    void shouldPublishOrderCreatedEvent() {
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<Order> result = orderService.createOrder(TEST_ORDER_ID, TEST_CORRELATION_ID);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        assertEquals("OrderCreated", eventMapCaptor.getValue().get("type"));
        assertEquals(TEST_ORDER_ID, eventMapCaptor.getValue().get("orderId"));
        assertEquals(TEST_CORRELATION_ID, eventMapCaptor.getValue().get("correlationId"));
    }

    /**
     * Verifies that createOrder handles a database failure.
     */
    @Test
    void shouldHandleDatabaseFailureDuringOrderCreation() {
        String errorMessage = "Database error";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.error(new RuntimeException(errorMessage)));
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<Order> result = orderService.createOrder(TEST_ORDER_ID, TEST_CORRELATION_ID);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(TEST_ORDER_ID) &&
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
        Mono<Order> result = orderService.createOrder(TEST_ORDER_ID, correlationId);

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
        Mono<Order> result = orderService.createOrder(orderId, TEST_CORRELATION_ID);

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
        OrderEvent event = new OrderCreatedEvent(TEST_ORDER_ID, TEST_CORRELATION_ID, TEST_EVENT_ID, "pending");

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
        OrderEvent event = new OrderCreatedEvent(TEST_ORDER_ID, TEST_CORRELATION_ID, TEST_EVENT_ID, "pending");

        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));
        when(meterRegistry.counter("event.publish.redis.success")).thenThrow(new RuntimeException("Metrics failure"));

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        verify(streamOps).add(eq("orders"), anyMap());
        verify(timerSample).stop(redisTimer);
    }

    @Test
    void shouldHandleMeterRegistryFailureDuringSagaStep() {
        SagaStep step = createSagaStep(TEST_ORDER_ID, TEST_CORRELATION_ID, TEST_EVENT_ID, TEST_QUANTITY);
        when(inventoryService.reserveStock(TEST_ORDER_ID, TEST_QUANTITY)).thenReturn(Mono.empty());
        when(meterRegistry.counter(eq("saga_step_success"), anyString(), anyString()))
                .thenThrow(new RuntimeException("Metrics failure"));
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectNextMatches(event ->
                        event.getType().equals(OrderEventType.STOCK_RESERVED) &&
                                event.getOrderId().equals(TEST_ORDER_ID))
                .verifyComplete();

        verify(inventoryService).reserveStock(TEST_ORDER_ID, TEST_QUANTITY);
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
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.error(new RuntimeException("Database failure")));
        reset(streamOps);
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.error(new RuntimeException("Redis failure")));
        when(redisTemplate.opsForList().leftPush(eq("failed-outbox-events"), any())).thenReturn(Mono.just(1L));

        Mono<Order> result = orderService.processOrder(TEST_ORDER_ID, TEST_QUANTITY, TEST_AMOUNT);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(TEST_ORDER_ID) &&
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
        when(meterRegistry.counter("orders_success")).thenThrow(new RuntimeException("Metrics failure"));
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<Order> result = orderService.createOrder(TEST_ORDER_ID, TEST_CORRELATION_ID);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(TEST_ORDER_ID) &&
                                order.status().equals("pending") &&
                                order.correlationId().equals(TEST_CORRELATION_ID))
                .verifyComplete();

        verify(databaseClient).sql(anyString());
    }

    // --- Event Publishing Tests ---

    /**
     * Verifies that publishEvent publishes an OrderCreated event correctly.
     */
    @Test
    void shouldPublishOrderCreatedEventSuccessfully() {
        OrderEvent event = new OrderCreatedEvent(TEST_ORDER_ID, TEST_CORRELATION_ID, TEST_EVENT_ID, "pending");

        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("record-id")));

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result).verifyComplete();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        Map<String, Object> capturedEvent = eventMapCaptor.getValue();
        assertEquals("OrderCreated", capturedEvent.get("type"));
        assertEquals(TEST_ORDER_ID, capturedEvent.get("orderId"));
        assertEquals(TEST_CORRELATION_ID, capturedEvent.get("correlationId"));
        assertEquals(TEST_EVENT_ID.toString(), capturedEvent.get("eventId"));
        assertTrue(capturedEvent.get("payload").toString().contains("pending"));

        verify(redisSuccessCounter).increment();
        verify(timerSample).stop(redisTimer);
        verify(redisFailureCounter, never()).increment();
        verify(redisRetryCounter, never()).increment();
    }
}