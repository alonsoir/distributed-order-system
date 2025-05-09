package com.example.order.service.unit;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.domain.Order;
import com.example.order.events.*;
import com.example.order.model.SagaStep;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.*;
import com.example.order.service.EventPublisher;
import com.example.order.utils.ReactiveUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("unit")
class SagaOrchestratorUnitTest {

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private CompensationManager compensationManager;

    @Mock
    private TransactionalOperator transactionalOperator;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private ResilienceManager resilienceManager;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private Counter counter;

    @Mock
    private Timer timer;

    @Mock
    private Timer.Sample timerSample;

    @InjectMocks
    private SagaOrchestratorImpl sagaOrchestrator;

    private static final Long ORDER_ID = 1234L;
    private static final String CORRELATION_ID = "corr-123";
    private static final String EVENT_ID = "event-123";
    private static final int QUANTITY = 10;
    private static final double AMOUNT = 100.0;

    @BeforeEach
    void setUp() {
        // Mock ResilienceManager behavior to return the mono unchanged
        when(resilienceManager.applyResilience(anyString()))
                .thenReturn(Function.identity());
        when(resilienceManager.applyResilience(any(CircuitBreakerCategory.class)))
                .thenReturn(Function.identity());

        // Mock TransactionalOperator to return the mono unchanged
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Mock basic database operation
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Mock metrics
        when(meterRegistry.counter(anyString(), any(String.class), any(String.class)))
                .thenReturn(counter);
        when(meterRegistry.timer(anyString(), any())).thenReturn(timer);

        // Mock ID generation
        when(idGenerator.generateOrderId()).thenReturn(ORDER_ID);
        when(idGenerator.generateCorrelationId()).thenReturn(CORRELATION_ID);
        when(idGenerator.generateEventId()).thenReturn(EVENT_ID);

        // Use Mockito spy to handle static methods in ReactiveUtils
        MockedStatic<ReactiveUtils> reactiveUtilsMock = mockStatic(ReactiveUtils.class);

        // Setup ReactiveUtils.withContextAndMetrics to pass through the Mono
        reactiveUtilsMock.when(() -> ReactiveUtils.withContextAndMetrics(
                        anyMap(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Runnable supplier = invocation.getArgument(1);
                    return supplier.run();
                });

        // Setup ReactiveUtils.withDiagnosticContext to pass through the Mono
        reactiveUtilsMock.when(() -> ReactiveUtils.withDiagnosticContext(
                        anyMap(), any()))
                .thenAnswer(invocation -> {
                    Runnable supplier = invocation.getArgument(1);
                    return supplier.run();
                });

        // Setup ReactiveUtils.createContext to return an empty map
        reactiveUtilsMock.when(() -> ReactiveUtils.createContext(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of());
    }

    @Test
    void testExecuteOrderSaga_HappyPath() {
        // Mock inventory service to return success
        when(inventoryService.reserveStock(any(), anyInt()))
                .thenReturn(Mono.empty());

        // Mock event publisher to return success
        when(eventPublisher.publishEvent(any(), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome(true, null, null)));

        // Execute test
        Mono<Order> result = sagaOrchestrator.executeOrderSaga(QUANTITY, AMOUNT);

        // Verify result
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(ORDER_ID) &&
                                order.status().equals("completed") &&
                                order.correlationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verify interactions
        verify(inventoryService).reserveStock(eq(ORDER_ID), eq(QUANTITY));
        verify(eventPublisher, times(2)).publishEvent(any(OrderEvent.class), anyString(), anyString());
    }

    @Test
    void testExecuteOrderSaga_InventoryFailure() {
        // Mock inventory service to fail
        RuntimeException inventoryException = new RuntimeException("Stock not available");
        when(inventoryService.reserveStock(any(), anyInt()))
                .thenReturn(Mono.error(inventoryException));

        // Mock compensation actions
        when(compensationManager.executeCompensation(any()))
                .thenReturn(Mono.empty());

        // Mock event publisher for failure event
        when(eventPublisher.publishEvent(any(OrderFailedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome(true, null, null)));

        // Execute test
        Mono<Order> result = sagaOrchestrator.executeOrderSaga(QUANTITY, AMOUNT);

        // Verify result
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(ORDER_ID) &&
                                order.status().equals("failed") &&
                                order.correlationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verify compensations triggered
        verify(compensationManager).executeCompensation(any(SagaStep.class));
    }

    @Test
    void testCreateOrder_Success() {
        // Mock event publisher
        when(eventPublisher.publishEvent(any(OrderCreatedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome(true, null, null)));

        // Execute test
        Mono<Order> result = sagaOrchestrator.createOrder(ORDER_ID, CORRELATION_ID, EVENT_ID);

        // Verify result
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(ORDER_ID) &&
                                order.status().equals("pending") &&
                                order.correlationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verify database interactions
        verify(databaseClient, times(3)).sql(anyString());
        verify(eventPublisher).publishEvent(any(OrderCreatedEvent.class), eq("createOrder"), eq(EventTopics.ORDER_CREATED.getTopic()));
    }

    @Test
    void testExecuteStep_Success() {
        // Create a test saga step
        SagaStep step = SagaStep.builder()
                .name("testStep")
                .topic("test.topic")
                .action(() -> Mono.empty())
                .orderId(ORDER_ID)
                .correlationId(CORRELATION_ID)
                .eventId(EVENT_ID)
                .successEvent(eventId -> new StockReservedEvent(ORDER_ID, CORRELATION_ID, eventId, QUANTITY))
                .build();

        // Mock event publisher
        when(eventPublisher.publishEvent(any(StockReservedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome(true, null, null)));

        // Execute test
        Mono<OrderEvent> result = sagaOrchestrator.executeStep(step);

        // Verify result
        StepVerifier.create(result)
                .expectNextMatches(event ->
                        event instanceof StockReservedEvent &&
                                event.getOrderId().equals(ORDER_ID) &&
                                event.getCorrelationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verify counter incremented
        verify(counter).increment();
    }

    @Test
    void testExecuteStep_Failure() {
        // Create a test saga step that fails
        RuntimeException stepException = new RuntimeException("Step failed");
        SagaStep step = SagaStep.builder()
                .name("failingStep")
                .topic("test.topic")
                .action(() -> Mono.error(stepException))
                .compensation(() -> Mono.empty())
                .orderId(ORDER_ID)
                .correlationId(CORRELATION_ID)
                .eventId(EVENT_ID)
                .successEvent(eventId -> new StockReservedEvent(ORDER_ID, CORRELATION_ID, eventId, QUANTITY))
                .build();

        // Mock event publisher for failure event
        when(eventPublisher.publishEvent(any(OrderFailedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome(true, null, null)));

        // Mock compensation
        when(compensationManager.executeCompensation(any()))
                .thenReturn(Mono.empty());

        // Execute test
        Mono<OrderEvent> result = sagaOrchestrator.executeStep(step);

        // Verify result includes error
        StepVerifier.create(result)
                .expectErrorMatches(e -> e instanceof RuntimeException &&
                        e.getMessage().equals("Step failed"))
                .verify();

        // Verify failure event published and compensation called
        verify(eventPublisher).publishEvent(any(OrderFailedEvent.class), eq("failedEvent"), eq(EventTopics.ORDER_FAILED.getTopic()));
        verify(compensationManager).executeCompensation(eq(step));
    }

    @Test
    void testCreateFailedEvent() {
        // Setup
        String reason = "Test failure reason";
        String externalRef = "external-123";

        // Mock event publisher
        when(eventPublisher.publishEvent(any(OrderFailedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome(true, null, null)));

        // Execute test
        Mono<Void> result = sagaOrchestrator.createFailedEvent(reason, externalRef);

        // Verify result
        StepVerifier.create(result)
                .verifyComplete();

        // Verify event published
        verify(eventPublisher).publishEvent(
                argThat(event ->
                        event instanceof OrderFailedEvent &&
                                ((OrderFailedEvent)event).getReason().equals(reason)),
                eq("failedEvent"),
                eq(EventTopics.ORDER_FAILED.getTopic())
        );
    }

    @Test
    void testExecuteStep_NullStep() {
        // Execute test with null step
        Mono<OrderEvent> result = sagaOrchestrator.executeStep(null);

        // Verify error thrown
        StepVerifier.create(result)
                .expectErrorMatches(e -> e instanceof IllegalArgumentException &&
                        e.getMessage().equals("SagaStep cannot be null"))
                .verify();
    }
}