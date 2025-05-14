package com.example.order.service.unit;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.domain.Order;
import com.example.order.events.*;
import com.example.order.model.SagaStep;
import com.example.order.model.SagaStepType;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.*;
import com.example.order.service.EventPublisher;
import com.example.order.utils.ReactiveUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.r2dbc.spi.Readable;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.core.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("unit")
class SagaOrchestratorAtMostOnceUnitTest {

    @Mock
    private DatabaseClient databaseClient;

    private DatabaseClient.GenericExecuteSpec executeSpec;

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

    private SagaOrchestratorAtMostOnceImpl2 sagaOrchestrator;

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

        // SOLUCIÓN ESPECÍFICA PARA MOCKITO
        // No intentamos usar map o one directamente - creamos un método personalizado
        mockDatabaseOperations();

        // Configuración del IdGenerator
        when(idGenerator.generateOrderId()).thenReturn(ORDER_ID);
        when(idGenerator.generateCorrelationId()).thenReturn(CORRELATION_ID);
        when(idGenerator.generateEventId()).thenReturn(EVENT_ID);
        when(idGenerator.generateExternalReference()).thenReturn(EXTERNAL_REF);

        // Mock estático para ReactiveUtils
        try (MockedStatic<ReactiveUtils> reactiveUtilsMock = mockStatic(ReactiveUtils.class)) {
            // Mock para ReactiveUtils.withContextAndMetrics
            reactiveUtilsMock.when(() -> ReactiveUtils.withContextAndMetrics(
                            anyMap(),
                            any(Supplier.class),
                            any(MeterRegistry.class),
                            anyString(),
                            any()))
                    .thenAnswer(invocation -> {
                        Supplier<?> supplier = invocation.getArgument(1);
                        return supplier.get();
                    });

            // Mock para ReactiveUtils.withDiagnosticContext
            reactiveUtilsMock.when(() -> ReactiveUtils.withDiagnosticContext(
                            anyMap(),
                            any(Supplier.class)))
                    .thenAnswer(invocation -> {
                        Supplier<?> supplier = invocation.getArgument(1);
                        return supplier.get();
                    });

            // Mock para ReactiveUtils.createContext
            reactiveUtilsMock.when(() -> ReactiveUtils.createContext(
                            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of());
        }

        // Crear instancia del objeto a probar
        sagaOrchestrator = new SagaOrchestratorAtMostOnceImpl2(
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

    /**
     * Método personalizado para mockear las operaciones de base de datos
     * más problemáticas sin intentar llamar a map() u one() directamente.
     */
    private void mockDatabaseOperations() {
        // En lugar de intentar mockear map y one directamente,
        // vamos a implementar un comportamiento personalizado utilizando
        // una implementación completa de GenericExecuteSpec

        // Crear una implementación completa de la interfaz
        executeSpec = new MockedGenericExecuteSpec();

        // Configurar el DatabaseClient con nuestro executeSpec personalizado
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
    }

    /**
     * Clase interna que implementa una versión básica de RowsFetchSpec
     * para poder devolver una implementación compatible
     */
    private class MockedRowsFetchSpec<T> implements RowsFetchSpec<T> {
        @Override
        public Mono<T> one() {
            return (Mono<T>) Mono.just(0);
        }

        @Override
        public Mono<T> first() {
            return (Mono<T>) Mono.just(0);
        }

        @Override
        public Flux<T> all() {
            return Flux.empty();
        }

        // Añadimos el método rowsUpdated para UpdatedRowsFetchSpec
        public Mono<Integer> rowsUpdated() {
            return Mono.just(1);
        }
    }

    /**
     * Implementación completa de GenericExecuteSpec para evitar problemas
     * de ambigüedad y falta de métodos en los mocks
     */
    private class MockedGenericExecuteSpec implements DatabaseClient.GenericExecuteSpec {
        @Override
        public DatabaseClient.GenericExecuteSpec bind(int index, Object value) {
            return this;
        }

        @Override
        public DatabaseClient.GenericExecuteSpec bindNull(int index, Class<?> type) {
            return this;
        }

        @Override
        public DatabaseClient.GenericExecuteSpec bind(String identifier, Object value) {
            return this;
        }

        @Override
        public DatabaseClient.GenericExecuteSpec bindNull(String identifier, Class<?> type) {
            return this;
        }

        @Override
        public DatabaseClient.GenericExecuteSpec filter(StatementFilterFunction filter) {
            return this;
        }

        @Override
        public <R> RowsFetchSpec<R> map(Function<? super Readable, R> mappingFunction) {
            // Devolver una instancia de nuestra clase personalizada
            return new MockedRowsFetchSpec<R>();
        }

        @Override
        public <R> RowsFetchSpec<R> mapValue(Class<R> mappedClass) {
            // Devolver una instancia de nuestra clase personalizada
            return new MockedRowsFetchSpec<R>();
        }

        @Override
        public <R> RowsFetchSpec<R> mapProperties(Class<R> mappedClass) {
            // Devolver una instancia de nuestra clase personalizada
            return new MockedRowsFetchSpec<R>();
        }

        @Override
        public <R> Flux<R> flatMap(Function<Result, Publisher<R>> mappingFunction) {
            return Flux.empty();
        }

        @Override
        public Mono<Void> then() {
            return Mono.empty();
        }

        @Override
        public DatabaseClient.GenericExecuteSpec bindValues(List<?> source) {
            return this;
        }

        @Override
        public DatabaseClient.GenericExecuteSpec bindValues(Map<String, ?> source) {
            return this;
        }

        @Override
        public DatabaseClient.GenericExecuteSpec bindProperties(Object source) {
            return this;
        }

        @Override
        public FetchSpec<Map<String, Object>> fetch() {
            return new FetchSpec<Map<String, Object>>() {
                @Override
                public Mono<Map<String, Object>> one() {
                    return Mono.just(Map.of());
                }

                @Override
                public Mono<Map<String, Object>> first() {
                    return Mono.just(Map.of());
                }

                @Override
                public Flux<Map<String, Object>> all() {
                    return Flux.empty();
                }

                // Añadimos el método rowsUpdated para UpdatedRowsFetchSpec
                public Mono<Long> rowsUpdated() {
                    return Mono.just(1L);
                }
            };
        }

        // Este método es específico de la implementación que estamos mockeando
        // y es uno de los métodos problemáticos para el test
        // Lo tipamos correctamente para evitar conflictos con la interfaz
        public <T> Mono<T> one() {
            return (Mono<T>) Mono.just(0);
        }

        // Este método es específico de la implementación que estamos mockeando
        // Corregimos el tipo de retorno para que sea compatible con la interfaz
        @Override
        public <T> RowsFetchSpec<T> map(BiFunction<Row, RowMetadata, T> mappingFunction) {
            // Devolver una instancia de nuestra clase personalizada
            return new MockedRowsFetchSpec<T>();
        }
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

        // Ejecutar test - con el parámetro quantity
        Mono<Order> result = sagaOrchestrator.createOrder(ORDER_ID, CORRELATION_ID, EVENT_ID, EXTERNAL_REF, QUANTITY);

        // Verificar resultado
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(ORDER_ID) &&
                                order.status().equals("pending") &&
                                order.correlationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verificar interacciones - no podemos verificar exactamente las llamadas SQL
        // ya que usamos implementación personalizada
        verify(eventPublisher).publishEvent(
                argThat(event -> event instanceof OrderCreatedEvent),
                eq("createOrder"),
                eq(EventTopics.ORDER_CREATED.getTopic())
        );
    }

    @Test
    void testCreateOrder_NullParameters() {
        // Ejecutar test con parámetros nulos - con el parámetro quantity
        Mono<Order> result = sagaOrchestrator.createOrder(null, CORRELATION_ID, EVENT_ID, EXTERNAL_REF, QUANTITY);

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

        // Verificar evento publicado
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
        /**
         * public OrderFailedEvent(Long orderId,
         *                             String correlationId,
         *                             String eventId,
         *                             SagaStepType step,
         *                             String reason,
         *                             String externalReference)
         */
        /*public class OrderFailedEvent implements OrderEvent {
    private final Long orderId;
    private final String correlationId;
    private final String eventId;
    private final SagaStepType step;
    private final String reason;
    private final String externalReference;
        * */
        OrderFailedEvent failedEvent = new OrderFailedEvent(
                ORDER_ID,
                CORRELATION_ID,
                EVENT_ID,
                SagaStepType.FAILED_EVENT,
                "Test failure",
                EXTERNAL_REF);

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
                eq(SagaStepType.FAILED_EVENT.name()),
                eq(EventTopics.ORDER_FAILED.getTopic())
        );
    }

    @Test
    void testPublishFailedEvent_PublishError() {
        // Crear evento de fallo
        /*public class OrderFailedEvent implements OrderEvent {
    private final Long orderId;
    private final String correlationId;
    private final String eventId;
    private final SagaStepType step;
    private final String reason;
    private final String externalReference;
        * */
        OrderFailedEvent failedEvent = new OrderFailedEvent(
                ORDER_ID,
                CORRELATION_ID,
                EVENT_ID,
                SagaStepType.FAILED_EVENT,
                "Test failure",
                EXTERNAL_REF);

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
        // Crear evento de prueba con constructor actualizado
        OrderCreatedEvent event = new OrderCreatedEvent(
                ORDER_ID, CORRELATION_ID, EVENT_ID, EXTERNAL_REF, QUANTITY);

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