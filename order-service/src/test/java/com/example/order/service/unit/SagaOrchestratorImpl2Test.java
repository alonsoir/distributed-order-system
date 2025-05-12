package com.example.order.service.unit;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.domain.Order;
import com.example.order.events.*;
import com.example.order.model.SagaStep;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.*;
import com.example.order.utils.ReactiveUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("unit")
class SagaOrchestratorImpl2Test {

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private GenericExecuteSpec executeSpec;

    @Mock
    private Timer.Sample timerSample;

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

    private SagaOrchestratorImpl2 sagaOrchestrator;

    private static final Long ORDER_ID = 1234L;
    private static final String CORRELATION_ID = "corr-123";
    private static final String EVENT_ID = "event-123";
    private static final String EXTERNAL_REF = "extern-123";
    private static final int QUANTITY = 10;
    private static final double AMOUNT = 100.0;

    @BeforeEach
    void setUp() {
        // Configuración de mocks
        when(meterRegistry.counter(anyString(), any(String[].class)))
                .thenReturn(counter);
        when(meterRegistry.counter(anyString()))
                .thenReturn(counter);
        when(meterRegistry.timer(anyString(), any(String[].class)))
                .thenReturn(timer);
        when(meterRegistry.timer(anyString()))
                .thenReturn(timer);

        when(Timer.start(any(MeterRegistry.class)))
                .thenReturn(timerSample);

        // Configuración de ResilienceManager
        when(resilienceManager.applyResilience(anyString()))
                .thenReturn(Function.identity());
        when(resilienceManager.applyResilience(any(CircuitBreakerCategory.class)))
                .thenReturn(Function.identity());

        // Configuración de TransactionalOperator
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Configuración del DatabaseClient
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Fix for mapping operation
        when(executeSpec.map(any(BiFunction.class))).thenReturn(executeSpec);
        when(executeSpec.one()).thenReturn(Mono.just(0));

        // Configuración del IdGenerator
        when(idGenerator.generateOrderId()).thenReturn(ORDER_ID);
        when(idGenerator.generateCorrelationId()).thenReturn(CORRELATION_ID);
        when(idGenerator.generateEventId()).thenReturn(EVENT_ID);
        when(idGenerator.generateExternalReference()).thenReturn(EXTERNAL_REF);

        // Mock estático para ReactiveUtils
        try (MockedStatic<ReactiveUtils> reactiveUtilsMock = mockStatic(ReactiveUtils.class)) {
            // Mock para ReactiveUtils.withContextAndMetrics
            reactiveUtilsMock.when(() -> ReactiveUtils.withContextAndMetrics(
                            anyMap(), any(Runnable.class), any(MeterRegistry.class), anyString(), any()))
                    .thenAnswer(invocation -> {
                        Runnable runnable = invocation.getArgument(1);
                        runnable.run();
                        return Mono.empty(); // Return a placeholder value
                    });

            // Mock para ReactiveUtils.withDiagnosticContext
            reactiveUtilsMock.when(() -> ReactiveUtils.withDiagnosticContext(
                            anyMap(), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        Runnable runnable = invocation.getArgument(1);
                        runnable.run();
                        return Mono.empty(); // Return a placeholder value
                    });

            // Mock para ReactiveUtils.createContext
            reactiveUtilsMock.when(() -> ReactiveUtils.createContext(
                            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of());
        }

        // Crear instancia del objeto a probar
        sagaOrchestrator = new SagaOrchestratorImpl2(
                databaseClient,
                transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                inventoryService,
                compensationManager
        );
    }

    @Test
    void testExecuteOrderSaga_HappyPath() {
        // Configuración de mocks para el caso de éxito
        when(inventoryService.reserveStock(any(), anyInt()))
                .thenReturn(Mono.empty());
        when(eventPublisher.publishEvent(any(), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome<>(true, null, null)));

        // Ejecutar test
        Mono<Order> result = sagaOrchestrator.executeOrderSaga(QUANTITY, AMOUNT);

        // Verificar resultado
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(ORDER_ID) &&
                                order.status().equals("completed") &&
                                order.correlationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verificar interacciones
        verify(inventoryService).reserveStock(eq(ORDER_ID), eq(QUANTITY));
        verify(counter, atLeastOnce()).increment();
        verify(timerSample, atLeastOnce()).stop(any());
    }

    @Test
    void testExecuteOrderSaga_InvalidQuantity() {
        // Ejecutar test con cantidad inválida
        Mono<Order> result = sagaOrchestrator.executeOrderSaga(-1, AMOUNT);

        // Verificar error
        StepVerifier.create(result)
                .expectErrorMatches(e ->
                        e instanceof IllegalArgumentException &&
                                e.getMessage().equals("Quantity must be positive"))
                .verify();

        // Verificar que no hubo otras interacciones
        verify(inventoryService, never()).reserveStock(any(), anyInt());
    }

    @Test
    void testExecuteOrderSaga_InventoryFailure() {
        // Configurar fallo en inventario
        RuntimeException inventoryException = new RuntimeException("Stock not available");
        when(inventoryService.reserveStock(any(), anyInt()))
                .thenReturn(Mono.error(inventoryException));

        // Configurar compensación
        when(compensationManager.executeCompensation(any()))
                .thenReturn(Mono.empty());

        // Configurar publicación de evento de fallo
        when(eventPublisher.publishEvent(any(OrderFailedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome<>(true, null, null)));

        // Ejecutar test
        Mono<Order> result = sagaOrchestrator.executeOrderSaga(QUANTITY, AMOUNT);

        // Verificar resultado
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(ORDER_ID) &&
                                order.status().equals("failed") &&
                                order.correlationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verificar compensación y fallo registrado
        verify(compensationManager).executeCompensation(any(SagaStep.class));
        verify(eventPublisher).publishEvent(
                argThat(event -> event instanceof OrderFailedEvent),
                eq("failedEvent"),
                eq(EventTopics.ORDER_FAILED.getTopic())
        );
    }

    @Test
    void testCreateOrder_Success() {
        // Configurar publicación de evento exitosa
        when(eventPublisher.publishEvent(any(OrderCreatedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome<>(true, null, null)));

        // Ejecutar test
        Mono<Order> result = sagaOrchestrator.createOrder(ORDER_ID, CORRELATION_ID, EVENT_ID, EXTERNAL_REF);

        // Verificar resultado
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(ORDER_ID) &&
                                order.status().equals("pending") &&
                                order.correlationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verificar interacciones
        verify(databaseClient, atLeast(3)).sql(anyString());
        verify(eventPublisher).publishEvent(
                argThat(event -> event instanceof OrderCreatedEvent),
                eq("createOrder"),
                eq(EventTopics.ORDER_CREATED.getTopic())
        );
    }

    @Test
    void testCreateOrder_NullParameters() {
        // Ejecutar test con parámetros nulos
        Mono<Order> result = sagaOrchestrator.createOrder(null, CORRELATION_ID, EVENT_ID, EXTERNAL_REF);

        // Verificar error
        StepVerifier.create(result)
                .expectErrorMatches(e ->
                        e instanceof IllegalArgumentException &&
                                e.getMessage().contains("cannot be null"))
                .verify();
    }

    @Test
    void testExecuteStep_Success() {
        // Crear paso de prueba
        SagaStep step = SagaStep.builder()
                .name("testStep")
                .topic("test.topic")
                .action(() -> Mono.empty())
                .orderId(ORDER_ID)
                .correlationId(CORRELATION_ID)
                .eventId(EVENT_ID)
                .externalReference(EXTERNAL_REF)
                .successEvent(eventId -> new StockReservedEvent(ORDER_ID, CORRELATION_ID, eventId, EXTERNAL_REF, QUANTITY))
                .build();

        // Configurar publicación de evento exitosa
        when(eventPublisher.publishEvent(any(StockReservedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome<>(true, null, null)));

        // Ejecutar test
        Mono<OrderEvent> result = sagaOrchestrator.executeStep(step);

        // Verificar resultado
        StepVerifier.create(result)
                .expectNextMatches(event ->
                        event instanceof StockReservedEvent &&
                                event.getOrderId().equals(ORDER_ID) &&
                                event.getCorrelationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verificar métricas incrementadas
        verify(counter).increment();
    }

    @Test
    void testExecuteStep_Failure() {
        // Crear paso que falla
        RuntimeException stepException = new RuntimeException("Step failed");
        SagaStep step = SagaStep.builder()
                .name("failingStep")
                .topic("test.topic")
                .action(() -> Mono.error(stepException))
                .compensation(() -> Mono.empty())
                .orderId(ORDER_ID)
                .correlationId(CORRELATION_ID)
                .eventId(EVENT_ID)
                .externalReference(EXTERNAL_REF)
                .successEvent(eventId -> new StockReservedEvent(ORDER_ID, CORRELATION_ID, eventId, EXTERNAL_REF, QUANTITY))
                .build();

        // Configurar manejo de error
        when(eventPublisher.publishEvent(any(OrderFailedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome<>(true, null, null)));

        when(compensationManager.executeCompensation(any()))
                .thenReturn(Mono.empty());

        // Ejecutar test
        Mono<OrderEvent> result = sagaOrchestrator.executeStep(step);

        // Verificar error propagado correctamente
        StepVerifier.create(result)
                .expectErrorMatches(e -> e.equals(stepException))
                .verify();

        // Verificar evento de fallo publicado
        verify(eventPublisher).publishEvent(
                argThat(event -> event instanceof OrderFailedEvent),
                eq("failedEvent"),
                eq(EventTopics.ORDER_FAILED.getTopic())
        );

        // Verificar compensación ejecutada
        verify(compensationManager).executeCompensation(eq(step));
    }

    @Test
    void testExecuteStep_NullStep() {
        // Ejecutar test con paso nulo
        Mono<OrderEvent> result = sagaOrchestrator.executeStep(null);

        // Verificar error
        StepVerifier.create(result)
                .expectErrorMatches(e ->
                        e instanceof IllegalArgumentException &&
                                e.getMessage().equals("SagaStep cannot be null"))
                .verify();
    }

    @Test
    void testCreateFailedEvent_Success() {
        // Configuración
        String reason = "Test failure reason";
        String externalRef = "external-123";

        // Configurar publicación de evento
        when(eventPublisher.publishEvent(any(OrderFailedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome<>(true, null, null)));

        // Ejecutar test
        Mono<Void> result = sagaOrchestrator.createFailedEvent(reason, externalRef);

        // Verificar resultado
        StepVerifier.create(result)
                .verifyComplete();

        // Verificar interacciones
        verify(databaseClient).sql(anyString());
        verify(eventPublisher).publishEvent(
                argThat(event ->
                        event instanceof OrderFailedEvent &&
                                ((OrderFailedEvent)event).getReason().equals(reason)),
                eq("failedEvent"),
                eq(EventTopics.ORDER_FAILED.getTopic())
        );
    }

    @Test
    void testCreateFailedEvent_NullReason() {
        // Ejecutar test con razón nula
        Mono<Void> result = sagaOrchestrator.createFailedEvent(null, "ref-123");

        // Verificar error
        StepVerifier.create(result)
                .expectErrorMatches(e ->
                        e instanceof IllegalArgumentException &&
                                e.getMessage().contains("reason cannot be null"))
                .verify();

        // Verificar que no se publicó evento
        verify(eventPublisher, never()).publishEvent(any(), anyString(), anyString());
    }

    @Test
    void testPublishFailedEvent_Success() {
        // Crear evento de fallo
        OrderFailedEvent failedEvent = new OrderFailedEvent(
                ORDER_ID, CORRELATION_ID, EVENT_ID, "testStep", "Test failure", EXTERNAL_REF);

        // Configurar publicación de evento exitosa
        when(eventPublisher.publishEvent(any(OrderFailedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome<>(true, null, null)));

        // Ejecutar test
        Mono<Void> result = sagaOrchestrator.publishFailedEvent(failedEvent);

        // Verificar resultado
        StepVerifier.create(result)
                .verifyComplete();

        // Verificar evento publicado
        verify(eventPublisher).publishEvent(
                eq(failedEvent),
                eq("failedEvent"),
                eq(EventTopics.ORDER_FAILED.getTopic())
        );
    }

    @Test
    void testPublishFailedEvent_PublishError() {
        // Crear evento de fallo
        OrderFailedEvent failedEvent = new OrderFailedEvent(
                ORDER_ID, CORRELATION_ID, EVENT_ID, "testStep", "Test failure", EXTERNAL_REF);

        // Configurar error en publicación
        RuntimeException publishException = new RuntimeException("Publish failed");
        when(eventPublisher.publishEvent(any(OrderFailedEvent.class), anyString(), anyString()))
                .thenReturn(Mono.error(publishException));

        // Ejecutar test
        Mono<Void> result = sagaOrchestrator.publishFailedEvent(failedEvent);

        // Verificar que completamos sin error a pesar del fallo interno
        // (crítico para no bloquear compensaciones)
        StepVerifier.create(result)
                .verifyComplete();

        // Verificar que se incrementó métrica crítica
        verify(counter).increment();
    }

    @Test
    void testPublishFailedEvent_NullEvent() {
        // Ejecutar test con evento nulo
        Mono<Void> result = sagaOrchestrator.publishFailedEvent(null);

        // Verificar error
        StepVerifier.create(result)
                .expectErrorMatches(e ->
                        e instanceof IllegalArgumentException &&
                                e.getMessage().contains("cannot be null"))
                .verify();

        // Verificar que no se publicó evento
        verify(eventPublisher, never()).publishEvent(any(), anyString(), anyString());
    }

    @Test
    void testPublishEvent_Success() {
        // Crear evento de prueba
        OrderCreatedEvent event = new OrderCreatedEvent(
                ORDER_ID, CORRELATION_ID, EVENT_ID, "pending");

        // Configurar publicación de evento exitosa
        when(eventPublisher.publishEvent(any(), anyString(), anyString()))
                .thenReturn(Mono.just(new EventPublishOutcome<>(true, event, null)));

        // Ejecutar test
        Mono<OrderEvent> result = sagaOrchestrator.publishEvent(
                event, "testStep", "test.topic");

        // Verificar resultado
        StepVerifier.create(result)
                .expectNext(event)
                .verifyComplete();

        // Verificar métricas registradas
        verify(timerSample).stop(any());
    }
}