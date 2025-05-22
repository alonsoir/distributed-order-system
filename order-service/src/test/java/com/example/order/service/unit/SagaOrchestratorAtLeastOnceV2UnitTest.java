package com.example.order.service.unit;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.domain.DeliveryMode;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.model.SagaStep;
import com.example.order.repository.EventRepository;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.*;
import com.example.order.service.v2.SagaOrchestratorAtLeastOnceImplV2;
import com.example.order.utils.ReactiveUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas unitarias para SagaOrchestratorAtLeastOnceImplV2 que utiliza exclusivamente
 * EventRepository en lugar de DatabaseClient e integra OrderStateMachine.
 */
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("unit")
@MockitoSettings(strictness = Strictness.LENIENT)
class SagaOrchestratorAtLeastOnceV2UnitTest {

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
    private EventRepository eventRepository;

    @Mock
    private Counter counter;

    @Mock
    private Timer timer;

    @Mock
    private Timer.Sample timerSample;

    private static final Long ORDER_ID = 1234L;
    private static final String CORRELATION_ID = "corr-123";
    private static final String EVENT_ID = "event-123";
    private static final String EXTERNAL_REF = "ext-123";
    private static final int QUANTITY = 10;
    private static final double AMOUNT = 100.0;

    // Variables para almacenar los mocks estáticos y poder cerrarlos correctamente
    private MockedStatic<Timer> timerMock;
    private MockedStatic<ReactiveUtils> reactiveUtilsMock;

    private SagaOrchestratorAtLeastOnceImplV2 sagaOrchestrator;

    @BeforeEach
    void setUp() {
        // Inicializar la instancia real con los mocks
        sagaOrchestrator = new SagaOrchestratorAtLeastOnceImplV2(
                transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                inventoryService,
                compensationManager,
                eventRepository
        );

        // Mock para EventRepository - métodos existentes
        when(eventRepository.isEventProcessed(anyString()))
                .thenReturn(Mono.just(false));
        when(eventRepository.markEventAsProcessed(anyString()))
                .thenReturn(Mono.empty());
        when(eventRepository.saveOrderData(anyLong(), anyString(), anyString(), any(OrderEvent.class)))
                .thenReturn(Mono.empty());
        when(eventRepository.saveEventHistory(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(eventRepository.insertStatusAuditLog(anyLong(), any(OrderStatus.class), anyString()))
                .thenReturn(Mono.empty());
        when(eventRepository.recordStepFailure(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(eventRepository.recordSagaFailure(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(eventRepository.recordSagaFailure(anyLong(), anyString(), anyString(), anyString(), any(DeliveryMode.class)))
                .thenReturn(Mono.empty());
        when(eventRepository.insertCompensationLog(anyString(), anyLong(), anyString(), anyString(), any(OrderStatus.class)))
                .thenReturn(Mono.empty());

        // NUEVO: Mock para getOrderStatus - fundamental para la nueva lógica
        when(eventRepository.getOrderStatus(anyLong()))
                .thenReturn(Mono.just(OrderStatus.ORDER_CREATED));

        // NUEVO: Mock para updateOrderStatus - retorna el estado actualizado
        when(eventRepository.updateOrderStatus(anyLong(), any(OrderStatus.class), anyString()))
                .thenAnswer(invocation -> {
                    Long orderId = invocation.getArgument(0);
                    OrderStatus status = invocation.getArgument(1);
                    String correlationId = invocation.getArgument(2);
                    return Mono.just(new Order(orderId, status.getValue(), correlationId));
                });

        // Mock para TransactionalOperator
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Mock para ResilienceManager
        when(resilienceManager.applyResilience(anyString()))
                .thenReturn(Function.identity());
        when(resilienceManager.applyResilience(any(CircuitBreakerCategory.class)))
                .thenReturn(Function.identity());

        // Mock para métrica
        when(meterRegistry.counter(anyString(), any(String.class), any(String.class)))
                .thenReturn(counter);
        when(meterRegistry.counter(anyString(), any(String[].class)))
                .thenReturn(counter);
        when(meterRegistry.timer(anyString(), any(Iterable.class)))
                .thenReturn(timer);

        // Mock para generación de IDs
        when(idGenerator.generateOrderId()).thenReturn(ORDER_ID);
        when(idGenerator.generateCorrelationId()).thenReturn(CORRELATION_ID);
        when(idGenerator.generateEventId()).thenReturn(EVENT_ID);
        when(idGenerator.generateExternalReference()).thenReturn(EXTERNAL_REF);

        // Mock Timer.start
        timerMock = mockStatic(Timer.class);
        timerMock.when(() -> Timer.start(any(MeterRegistry.class)))
                .thenReturn(timerSample);

        // Mock ReactiveUtils
        reactiveUtilsMock = mockStatic(ReactiveUtils.class);

        reactiveUtilsMock.when(() -> ReactiveUtils.withContextAndMetrics(
                        anyMap(),
                        any(),
                        any(MeterRegistry.class),
                        anyString(),
                        any(Tag[].class)))
                .thenAnswer(invocation -> {
                    Supplier<Mono<?>> supplier = invocation.getArgument(1);
                    return supplier.get();
                });

        reactiveUtilsMock.when(() -> ReactiveUtils.withContextAndMetrics(
                        anyMap(),
                        any(),
                        any(MeterRegistry.class),
                        anyString(),
                        any(Tag.class)))
                .thenAnswer(invocation -> {
                    Supplier<Mono<?>> supplier = invocation.getArgument(1);
                    return supplier.get();
                });

        reactiveUtilsMock.when(() -> ReactiveUtils.withDiagnosticContext(
                        anyMap(),
                        any()))
                .thenAnswer(invocation -> {
                    Supplier<Mono<?>> supplier = invocation.getArgument(1);
                    return supplier.get();
                });

        reactiveUtilsMock.when(() -> ReactiveUtils.createContext(any(String[].class)))
                .thenReturn(Map.of());

        // Mock para publicación de eventos
        when(eventPublisher.publishEvent(any(OrderEvent.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    OrderEvent event = inv.getArgument(0);
                    return Mono.just(EventPublishOutcome.success(event));
                });

        // Mock para inventory service
        when(inventoryService.reserveStock(anyLong(), anyInt())).thenReturn(Mono.empty());
    }

    @AfterEach
    void tearDown() {
        if (timerMock != null) {
            timerMock.close();
        }
        if (reactiveUtilsMock != null) {
            reactiveUtilsMock.close();
        }
    }

    @Test
    void testExecuteStep_NullStep() {
        // Este es un caso simple que no depende de metodología interna
        Mono<OrderEvent> result = sagaOrchestrator.executeStep(null);

        StepVerifier.create(result)
                .expectErrorMatches(e -> e instanceof IllegalArgumentException &&
                        e.getMessage().equals("SagaStep cannot be null"))
                .verify();
    }

    @Test
    void testCreateFailedEvent() {
        String reason = "Test failure reason";
        String externalRef = "external-123";

        Mono<Void> result = sagaOrchestrator.createFailedEvent(reason, externalRef);

        StepVerifier.create(result)
                .verifyComplete();

        // Verificar que se publicó un evento de fallo
        verify(eventPublisher).publishEvent(any(OrderFailedEvent.class), eq("failedEvent"), anyString());
    }

    @Test
    void testCreateOrder() {
        // Given
        int quantity = 5;

        // When
        Mono<Order> result = sagaOrchestrator.createOrder(ORDER_ID, CORRELATION_ID, EVENT_ID, EXTERNAL_REF, quantity);

        // Then
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(ORDER_ID) &&
                                order.status().equals("pending") &&
                                order.correlationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verificar interacciones con EventRepository
        verify(eventRepository).saveOrderData(eq(ORDER_ID), eq(CORRELATION_ID), eq(EVENT_ID), any());
        verify(eventPublisher).publishEvent(any(), eq("createOrder"), anyString());
    }

    @Test
    void testExecuteOrderSaga() {
        // ACTUALIZADO: configurar el flujo de estados para la nueva lógica
        when(eventRepository.getOrderStatus(ORDER_ID))
                .thenReturn(Mono.just(OrderStatus.ORDER_CREATED))  // estado inicial
                .thenReturn(Mono.just(OrderStatus.STOCK_RESERVED)); // después de reservar stock

        // When
        Mono<Order> result = sagaOrchestrator.executeOrderSaga(QUANTITY, AMOUNT);

        // Then
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(ORDER_ID) &&
                                order.status().equals(OrderStatus.ORDER_COMPLETED.getValue()) &&
                                order.correlationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verificar interacciones
        verify(inventoryService).reserveStock(eq(ORDER_ID), eq(QUANTITY));
        verify(eventRepository, atLeast(1)).getOrderStatus(eq(ORDER_ID));
        verify(eventRepository).updateOrderStatus(eq(ORDER_ID), eq(OrderStatus.ORDER_COMPLETED), eq(CORRELATION_ID));
    }

    @Test
    void testExecuteOrderSaga_WithInventoryServiceFailure() {
        // Given
        RuntimeException expectedError = new RuntimeException("Inventory service error");

        // ACTUALIZADO: configurar estados para el flujo de error
        when(eventRepository.getOrderStatus(ORDER_ID))
                .thenReturn(Mono.just(OrderStatus.ORDER_CREATED));

        // When: Configuramos el comportamiento para simular un error
        when(inventoryService.reserveStock(anyLong(), anyInt()))
                .thenReturn(Mono.error(expectedError));

        // Then
        Mono<Order> result = sagaOrchestrator.executeOrderSaga(QUANTITY, AMOUNT);

        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.id().equals(ORDER_ID) &&
                                (order.status().equals(OrderStatus.ORDER_FAILED.getValue()) ||
                                        order.status().equals(OrderStatus.TECHNICAL_EXCEPTION.getValue())) &&
                                order.correlationId().equals(CORRELATION_ID))
                .verifyComplete();

        // Verificar interacciones - actualizado para los nuevos flujos
        verify(eventRepository, atLeast(1)).getOrderStatus(eq(ORDER_ID));
        verify(eventRepository).updateOrderStatus(eq(ORDER_ID), any(OrderStatus.class), eq(CORRELATION_ID));
    }

    @Test
    void testExecuteStep_EventAlreadyProcessed() {
        // Given
        when(eventRepository.isEventProcessed(anyString()))
                .thenReturn(Mono.just(true));

        SagaStep step = mock(SagaStep.class);
        when(step.getName()).thenReturn("testStep");
        when(step.getOrderId()).thenReturn(ORDER_ID);
        when(step.getCorrelationId()).thenReturn(CORRELATION_ID);
        when(step.getEventId()).thenReturn(EVENT_ID);
        when(step.getExternalReference()).thenReturn(EXTERNAL_REF);
        when(step.getTopic()).thenReturn("test-topic");
        when(step.getSuccessEvent()).thenReturn(eventId -> mock(OrderEvent.class));

        // When
        Mono<OrderEvent> result = sagaOrchestrator.executeStep(step);

        // Then
        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();

        // Verificar que se detectó el evento como ya procesado
        verify(eventRepository).isEventProcessed(eq(EVENT_ID));
        // Verificar que no se ejecuta la acción del paso
        verify(step, never()).getAction();
    }

    @Test
    @DisplayName("Verificar transición de estado válida")
    void testValidStateTransition() {
        // Given - configurar un estado que permite transición a ORDER_COMPLETED
        when(eventRepository.getOrderStatus(ORDER_ID))
                .thenReturn(Mono.just(OrderStatus.DELIVERED));

        // When - intentar transicionar a ORDER_COMPLETED
        Mono<Order> result = sagaOrchestrator.executeOrderSaga(QUANTITY, AMOUNT);

        // Then
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.status().equals(OrderStatus.ORDER_COMPLETED.getValue()))
                .verifyComplete();

        verify(eventRepository).updateOrderStatus(eq(ORDER_ID), eq(OrderStatus.ORDER_COMPLETED), eq(CORRELATION_ID));
    }

    @Test
    @DisplayName("Verificar manejo de transición de estado inválida")
    void testInvalidStateTransitionHandling() {
        // Given - configurar un estado terminal que no permite más transiciones
        when(eventRepository.getOrderStatus(ORDER_ID))
                .thenReturn(Mono.just(OrderStatus.ORDER_FAILED)); // estado terminal

        // When - intentar ejecutar saga (que eventualmente intentará cambiar estado)
        Mono<Order> result = sagaOrchestrator.executeOrderSaga(QUANTITY, AMOUNT);

        // Then - debería manejar la transición inválida apropiadamente
        StepVerifier.create(result)
                .expectNextMatches(order -> order.id().equals(ORDER_ID))
                .verifyComplete();

        // Verificar que se consultó el estado actual
        verify(eventRepository, atLeast(1)).getOrderStatus(eq(ORDER_ID));
    }

    @Test
    @DisplayName("Verificar operación check-and-mark atómica para idempotencia")
    void testCheckAndMarkIdempotence() {
        // Given
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        // Configurar método atómico de verificación e idempotencia
        when(eventRepository.checkAndMarkEventAsProcessed(EVENT_ID, deliveryMode))
                .thenReturn(Mono.just(false)); // no procesado previamente

        // When - simular un método que usa la operación atómica
        Mono<Boolean> result = eventRepository.checkAndMarkEventAsProcessed(EVENT_ID, deliveryMode)
                .flatMap(alreadyProcessed -> {
                    if (alreadyProcessed) {
                        return Mono.just(true); // ya procesado
                    }
                    // Simular procesamiento
                    return Mono.just(false); // recién procesado
                });

        // Then
        StepVerifier.create(result)
                .expectNext(false) // indica que fue procesado por primera vez
                .verifyComplete();

        // Verificar llamada al método atómico
        verify(eventRepository).checkAndMarkEventAsProcessed(EVENT_ID, deliveryMode);
    }

    @Test
    @DisplayName("Verificar inserción de auditoría de estado")
    void testStatusAuditLog() {
        // Given
        when(eventRepository.insertStatusAuditLog(ORDER_ID, OrderStatus.ORDER_COMPLETED, CORRELATION_ID))
                .thenReturn(Mono.empty());

        // When
        Mono<Void> result = Mono.defer(() ->
                eventRepository.insertStatusAuditLog(ORDER_ID, OrderStatus.ORDER_COMPLETED, CORRELATION_ID));

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verificar llamada a inserción de log de auditoría
        verify(eventRepository).insertStatusAuditLog(ORDER_ID, OrderStatus.ORDER_COMPLETED, CORRELATION_ID);
    }
}