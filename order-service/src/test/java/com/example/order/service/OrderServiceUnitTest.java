package com.example.order.service;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

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
    private Counter sagaStepSuccessCounter;

    @Mock
    private Counter sagaStepFailedCounter;

    @Mock
    private Timer redisTimer;

    @Mock
    private Timer outboxTimer;

    @Mock
    private Timer.Sample timerSample;

    @Mock
    private TransactionalOperator transactionalOperator;

    @InjectMocks
    private OrderService orderService;

    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
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
        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.just(RecordId.of("0-0")));
    }

    @AfterEach
    void tearDown() throws Exception {
        closeable.close();
    }

    @Test
    void shouldProcessOrderSuccessfully() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(databaseClient.sql(sqlCaptor.capture())).thenReturn(executeSpec);

        Mono<Order> result = orderService.processOrder(orderId, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals("completed") &&
                                order.correlationId() != null)
                .verifyComplete();

        // Verificar consultas SQL
        assertEquals(5, sqlCaptor.getAllValues().size()); // +1 por el UPDATE
        assertEquals("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :status, :correlationId)", sqlCaptor.getAllValues().get(0));
        assertEquals("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)", sqlCaptor.getAllValues().get(1));
        assertEquals("INSERT INTO processed_events (event_id) VALUES (:eventId)", sqlCaptor.getAllValues().get(2));
        assertEquals("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)", sqlCaptor.getAllValues().get(3));
        assertEquals("UPDATE orders SET status = :status WHERE id = :id", sqlCaptor.getAllValues().get(4));

        // Verificar parámetros del UPDATE
        ArgumentCaptor<String> bindKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bindValueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(executeSpec, atLeastOnce()).bind(bindKeyCaptor.capture(), bindValueCaptor.capture());
        assertTrue(bindKeyCaptor.getAllValues().contains("status"));
        assertTrue(bindValueCaptor.getAllValues().contains("completed"));
        assertTrue(bindKeyCaptor.getAllValues().contains("id"));
        assertTrue(bindValueCaptor.getAllValues().contains(orderId));

        verify(ordersSuccessCounter).increment();
        verify(redisSuccessCounter, times(2)).increment();
        verify(sagaStepSuccessCounter).increment();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps, times(2)).add(eq("orders"), eventMapCaptor.capture());
        Map<String, Object> stockReservedEvent = eventMapCaptor.getAllValues().get(1);
        assertEquals(orderId, stockReservedEvent.get("orderId"));
        assertEquals("StockReserved", stockReservedEvent.get("type"));

        verify(inventoryService).reserveStock(orderId, quantity);
        verify(timerSample, times(2)).stop(redisTimer);
    }

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
        assertTrue(((String) eventMapCaptor.getValue().get("payload")).contains("circuit_breaker"));

        verify(inventoryService, never()).reserveStock(anyLong(), anyInt());
    }

    @Test
    void shouldCreateOrderSuccessfully() {
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

        assertEquals(3, sqlCaptor.getAllValues().size());
        assertEquals("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :status, :correlationId)", sqlCaptor.getAllValues().get(0));
        assertEquals("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)", sqlCaptor.getAllValues().get(1));
        assertEquals("INSERT INTO processed_events (event_id) VALUES (:eventId)", sqlCaptor.getAllValues().get(2));

        ArgumentCaptor<String> bindKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bindValueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(executeSpec, atLeastOnce()).bind(bindKeyCaptor.capture(), bindValueCaptor.capture());
        assertTrue(bindKeyCaptor.getAllValues().contains("id"));
        assertTrue(bindValueCaptor.getAllValues().contains(orderId));
        assertTrue(bindKeyCaptor.getAllValues().contains("event_type"));
        assertTrue(bindValueCaptor.getAllValues().contains("OrderCreated"));

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        Map<String, Object> event = eventMapCaptor.getValue();
        assertEquals(orderId, event.get("orderId"));
        assertEquals("OrderCreated", event.get("type"));
        assertEquals(correlationId, event.get("correlationId"));

        verify(redisSuccessCounter).increment();
        verify(timerSample).stop(redisTimer);
    }

    @Test
    void shouldHandleCreateOrderFailure() {
        Long orderId = 1L;
        String correlationId = "test-correlation-id";
        String errorMessage = "Database error";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.bind(anyString(), any())).thenReturn(bindSpec);
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
        Map<String, Object> event = eventMapCaptor.getValue();
        assertEquals("OrderFailed", event.get("type"));
        assertTrue(((String) event.get("payload")).contains(errorMessage));
        assertTrue(((String) event.get("payload")).contains("createOrder"));

        verify(redisSuccessCounter).increment();
        verify(timerSample).stop(redisTimer);
    }

    @Test
    void shouldPublishEventSuccessfully() {
        OrderService.OrderCreatedEvent event = new OrderService.OrderCreatedEvent(1L, "corr-id", "event-id", "pending");

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result)
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        Map<String, Object> eventMap = eventMapCaptor.getValue();
        assertEquals(1L, eventMap.get("orderId"));
        assertEquals("OrderCreated", eventMap.get("type"));
        assertEquals("corr-id", eventMap.get("correlationId"));
        assertEquals("event-id", eventMap.get("eventId"));

        verify(redisSuccessCounter).increment();
        verify(redisFailureCounter, never()).increment();
        verify(timerSample).stop(redisTimer);
    }

    @Test
    void shouldPublishEventToOutboxOnRedisFailure() {
        OrderService.OrderCreatedEvent event = new OrderService.OrderCreatedEvent(1L, "corr-id", "event-id", "pending");
        String errorMessage = "Redis connection failed";

        when(streamOps.add(anyString(), anyMap())).thenReturn(Mono.error(new RuntimeException(errorMessage)));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(databaseClient.sql(sqlCaptor.capture())).thenReturn(executeSpec);

        Mono<Void> result = orderService.publishEvent(event);

        StepVerifier.create(result)
                .verifyComplete();

        verify(streamOps).add(eq("orders"), anyMap());
        verify(redisFailureCounter).increment();
        verify(redisSuccessCounter, never()).increment();

        assertEquals("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)", sqlCaptor.getValue());

        ArgumentCaptor<String> bindKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bindValueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(executeSpec).bind(bindKeyCaptor.capture(), bindValueCaptor.capture());
        assertTrue(bindKeyCaptor.getAllValues().contains("event_type"));
        assertTrue(bindValueCaptor.getAllValues().contains("OrderCreated"));
        assertTrue(bindKeyCaptor.getAllValues().contains("correlationId"));
        assertTrue(bindValueCaptor.getAllValues().contains("corr-id"));
        assertTrue(bindKeyCaptor.getAllValues().contains("eventId"));
        assertTrue(bindValueCaptor.getAllValues().contains("event-id"));

        verify(outboxSuccessCounter).increment();
        verify(outboxFailureCounter, never()).increment();
        verify(timerSample).stop(outboxTimer);
    }

    @Test
    void shouldFailToPublishEventWithInvalidData() {
        // Evento con correlationId nulo
        OrderService.OrderCreatedEvent invalidEvent = new OrderService.OrderCreatedEvent(1L, null, "event-id", "pending");

        Mono<Void> result = orderService.publishEvent(invalidEvent);

        StepVerifier.create(result)
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException &&
                                throwable.getMessage().contains("CorrelationId cannot be null or empty"))
                .verify();

        verify(streamOps, never()).add(anyString(), anyMap());
        verify(redisSuccessCounter, never()).increment();
        verify(redisFailureCounter, never()).increment();
    }

    @Test
    void shouldExecuteStepSuccessfully() {
        Long orderId = 1L;
        String correlationId = "corr-id";
        String eventId = UUID.randomUUID().toString();
        int quantity = 10;

        OrderService.SagaStep step = OrderService.SagaStep.builder()
                .name("reserveStock")
                .action(() -> inventoryService.reserveStock(orderId, quantity))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                .successEvent(eid -> new OrderService.StockReservedEvent(orderId, correlationId, eid, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(databaseClient.sql(sqlCaptor.capture())).thenReturn(executeSpec);

        Mono<OrderService.OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectNextMatches(event ->
                        event.getType().equals("StockReserved") &&
                                event.getOrderId().equals(orderId) &&
                                event.getCorrelationId().equals(correlationId))
                .verifyComplete();

        assertEquals(2, sqlCaptor.getAllValues().size());
        assertEquals("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)", sqlCaptor.getAllValues().get(0));
        assertEquals("INSERT INTO processed_events (event_id) VALUES (:eventId)", sqlCaptor.getAllValues().get(1));

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        Map<String, Object> eventMap = eventMapCaptor.getValue();
        assertEquals("StockReserved", eventMap.get("type"));
        assertEquals(orderId, eventMap.get("orderId"));
        assertEquals(correlationId, eventMap.get("correlationId"));
        assertEquals(eventId, eventMap.get("eventId"));

        verify(sagaStepSuccessCounter).increment();
        verify(sagaStepFailedCounter, never()).increment();
        verify(redisSuccessCounter).increment();
        verify(timerSample).stop(redisTimer);
        verify(inventoryService).reserveStock(orderId, quantity);
        verify(inventoryService, never()).releaseStock(anyLong(), anyInt());
    }

    @Test
    void shouldHandleStepActionFailure() {
        Long orderId = 1L;
        String correlationId = "corr-id";
        String eventId = UUID.randomUUID().toString();
        int quantity = 10;
        String errorMessage = "Insufficient stock";

        OrderService.SagaStep step = OrderService.SagaStep.builder()
                .name("reserveStock")
                .action(() -> inventoryService.reserveStock(orderId, quantity))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                .successEvent(eid -> new OrderService.StockReservedEvent(orderId, correlationId, eid, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.error(new RuntimeException(errorMessage)));
        when(inventoryService.releaseStock(orderId, quantity)).thenReturn(Mono.empty());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(databaseClient.sql(sqlCaptor.capture())).thenReturn(executeSpec);

        Mono<OrderService.OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable.getMessage().contains(errorMessage))
                .verify();

        assertEquals(1, sqlCaptor.getAllValues().size());
        assertEquals("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)", sqlCaptor.getAllValues().get(0));

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        Map<String, Object> eventMap = eventMapCaptor.getValue();
        assertEquals("OrderFailed", eventMap.get("type"));
        assertTrue(((String) eventMap.get("payload")).contains(errorMessage));
        assertTrue(((String) eventMap.get("payload")).contains("reserveStock"));

        verify(sagaStepFailedCounter).increment();
        verify(sagaStepSuccessCounter, never()).increment();
        verify(redisSuccessCounter).increment();
        verify(inventoryService).reserveStock(orderId, quantity);
        verify(inventoryService).releaseStock(orderId, quantity);
    }

    @Test
    void shouldHandleStepCompensationFailure() {
        Long orderId = 1L;
        String correlationId = "corr-id";
        String eventId = UUID.randomUUID().toString();
        int quantity = 10;
        String actionError = "Insufficient stock";
        String compensationError = "Compensation failed";

        OrderService.SagaStep step = OrderService.SagaStep.builder()
                .name("reserveStock")
                .action(() -> inventoryService.reserveStock(orderId, quantity))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                .successEvent(eid -> new OrderService.StockReservedEvent(orderId, correlationId, eid, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.error(new RuntimeException(actionError)));
        when(inventoryService.releaseStock(orderId, quantity))
                .thenReturn(Mono.error(new RuntimeException(compensationError))) // Primer intento falla
                .thenReturn(Mono.error(new RuntimeException(compensationError))) // Segundo intento falla
                .thenReturn(Mono.error(new RuntimeException(compensationError))); // Tercer intento falla

        when(redisTemplate.opsForList().leftPush(eq("failed-compensations"), any())).thenReturn(Mono.just(1L));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(databaseClient.sql(sqlCaptor.capture())).thenReturn(executeSpec);

        Mono<OrderService.OrderEvent> result = orderService.executeStep(step);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable.getMessage().contains(actionError))
                .verify();

        assertEquals(1, sqlCaptor.getAllValues().size());
        assertEquals("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)", sqlCaptor.getAllValues().get(0));

        ArgumentCaptor<Map<String, Object>> eventMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("orders"), eventMapCaptor.capture());
        Map<String, Object> eventMap = eventMapCaptor.getValue();
        assertEquals("OrderFailed", eventMap.get("type"));
        assertTrue(((String) eventMap.get("payload")).contains(actionError));

        ArgumentCaptor<OrderService.CompensationTask> compensationTaskCaptor = ArgumentCaptor.forClass(OrderService.CompensationTask.class);
        verify(redisTemplate.opsForList()).leftPush(eq("failed-compensations"), compensationTaskCaptor.capture());
        OrderService.CompensationTask task = compensationTaskCaptor.getValue();
        assertEquals(orderId, task.orderId());
        assertEquals(correlationId, task.correlationId());
        assertEquals("reserveStock", task.step());
        assertEquals(compensationError, task.error());

        verify(inventoryService).reserveStock(orderId, quantity);
        verify(inventoryService, times(3)).releaseStock(orderId, quantity); // 3 intentos
        verify(sagaStepFailedCounter).increment();
        verify(redisSuccessCounter).increment();
        verify(meterRegistry, times(2)).counter(eq("saga_compensation_retry"), eq("step"), eq("reserveStock"));
    }

    @Test
    void shouldFailToCreateSagaStepWithNullFields() {
        assertThrows(NullPointerException.class, () -> OrderService.SagaStep.builder()
                .name(null)
                .action(() -> Mono.empty())
                .compensation(() -> Mono.empty())
                .successEvent(eid -> new OrderService.StockReservedEvent(1L, "corr-id", eid, 10))
                .orderId(1L)
                .correlationId("corr-id")
                .eventId("event-id")
                .build(), "Step name cannot be null");

        assertThrows(NullPointerException.class, () -> OrderService.SagaStep.builder()
                .name("reserveStock")
                .action(null)
                .compensation(() -> Mono.empty())
                .successEvent(eid -> new OrderService.StockReservedEvent(1L, "corr-id", eid, 10))
                .orderId(1L)
                .correlationId("corr-id")
                .eventId("event-id")
                .build(), "Action cannot be null");
    }
    @Test
    void shouldFailToProcessOrderWithInvalidOrderId() {
        Long invalidOrderId = 0L;
        int quantity = 10;
        double amount = 100.0;

        Mono<Order> result = orderService.processOrder(invalidOrderId, quantity, amount);

        StepVerifier.create(result)
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException &&
                                throwable.getMessage().contains("OrderId must be positive"))
                .verify();

        verify(databaseClient, never()).sql(anyString());
        verify(inventoryService, never()).reserveStock(anyLong(), anyInt());
    }

    @Test
    void shouldFailToPublishEventWithNullType() {
        OrderService.OrderEvent invalidEvent = new OrderService.OrderEvent() {
            @Override
            public Long getOrderId() { return 1L; }
            @Override
            public String getCorrelationId() { return "corr-id"; }
            @Override
            public String getEventId() { return "event-id"; }
            @Override
            public String getType() { return null; }
            @Override
            public String toJson() { return "{}"; }
        };

        Mono<Void> result = orderService.publishEvent(invalidEvent);

        StepVerifier.create(result)
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException &&
                                throwable.getMessage().contains("Event type cannot be null or empty"))
                .verify();

        verify(streamOps, never()).add(anyString(), anyMap());
    }

    @Test
    void shouldHandleOnTimeoutCorrectly() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.error(new RuntimeException("Timeout")));

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
        assertTrue(((String) eventMapCaptor.getValue().get("payload")).contains("timeout"));
    }
}