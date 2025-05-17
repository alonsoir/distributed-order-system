package com.example.order.service.unit;

import com.example.order.domain.Order;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderCreatedEvent;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.model.SagaStep;
import com.example.order.model.SagaStepType;
import com.example.order.repository.EventRepository;
import com.example.order.service.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Prueba unitaria minimalista para SagaOrchestratorAtMostOnceImpl2.
 * Se centra exclusivamente en los métodos públicos definidos en la interfaz SagaOrchestrator.
 */
@ActiveProfiles("unit")
@Deprecated(since = "2.0.0", forRemoval = true)
class SagaOrchestratorAtMostOnceUnitTest {

    // Sistema bajo prueba referenciado a través de la interfaz
    private SagaOrchestrator sagaOrchestrator;

    // Mocks de dependencias
    private DatabaseClient databaseClient;
    private TransactionalOperator transactionalOperator;
    private MeterRegistry meterRegistry;
    private IdGenerator idGenerator;
    private InventoryService inventoryService;
    private EventPublisher eventPublisher;
    private EventRepository eventRepository; // Nuevo mock

    // Constantes para pruebas
    private final Long orderId = 1234L;
    private final String correlationId = UUID.randomUUID().toString();
    private final String eventId = UUID.randomUUID().toString();
    private final String externalReference = UUID.randomUUID().toString();
    private final int quantity = 5;
    private final double amount = 100.0;

    @BeforeEach
    void setUp() {
        // Inicializar mocks
        databaseClient = mock(DatabaseClient.class);
        transactionalOperator = mock(TransactionalOperator.class);
        meterRegistry = new SimpleMeterRegistry();
        idGenerator = mock(IdGenerator.class);
        inventoryService = mock(InventoryService.class);
        eventPublisher = mock(EventPublisher.class);

        // Nuevo mock para EventRepository
        eventRepository = mock(EventRepository.class);

        // Mock de ResilienceManager con implementación directa para evitar ambigüedades
        var resilienceManager = mock(com.example.order.resilience.ResilienceManager.class);

        // Mock response cuando se utiliza transactional
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Mock respuesta de funciones de database
        DatabaseClient.GenericExecuteSpec mockSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(mockSpec);
        when(mockSpec.bind(anyString(), any())).thenReturn(mockSpec);
        when(mockSpec.then()).thenReturn(Mono.empty());

        // Mock generación de IDs
        when(idGenerator.generateOrderId()).thenReturn(orderId);
        when(idGenerator.generateCorrelationId()).thenReturn(correlationId);
        when(idGenerator.generateEventId()).thenReturn(eventId);
        when(idGenerator.generateExternalReference()).thenReturn(externalReference);

        // Mock respuesta de ResilienceManager
        when(resilienceManager.applyResilience(anyString()))
                .thenReturn(Function.identity());

        // Mock respuesta de EventPublisher
        OrderEvent mockEvent = mock(OrderEvent.class);
        EventPublishOutcome<OrderEvent> outcome = EventPublishOutcome.success(mockEvent);
        when(eventPublisher.publishEvent(any(OrderEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(outcome));

        // Mock respuesta de InventoryService
        when(inventoryService.reserveStock(anyLong(), anyInt()))
                .thenReturn(Mono.empty());

        // Mock respuesta de EventRepository
        when(eventRepository.isEventProcessed(anyString())).thenReturn(Mono.just(false));
        when(eventRepository.findOrderById(anyLong())).thenReturn(Mono.just(new Order(orderId, "pending", correlationId)));
        when(eventRepository.saveOrderData(anyLong(), anyString(), anyString(), any(OrderEvent.class))).thenReturn(Mono.empty());
        when(eventRepository.updateOrderStatus(anyLong(), anyString(), anyString())).thenReturn(Mono.just(new Order(orderId, "pending", correlationId)));
        when(eventRepository.insertStatusAuditLog(anyLong(), anyString(), anyString())).thenReturn(Mono.empty());
        when(eventRepository.saveEventHistory(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(eventRepository.recordSagaFailure(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(eventRepository.recordStepFailure(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(eventRepository.insertCompensationLog(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(Mono.empty());

        // CompensationManager
        CompensationManager compensationManager = mock(CompensationManager.class);

        // Crear el sistema bajo prueba
        sagaOrchestrator = new SagaOrchestratorAtMostOnceImpl2(
                databaseClient,
                transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                inventoryService,
                compensationManager,
                eventRepository // Incluir el nuevo parámetro
        );
    }

    @Test
    void testCreateOrder_Success() {
        // Arrange
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.createOrder(orderId, correlationId, eventId, externalReference, quantity))
                .assertNext(order -> {
                    assertNotNull(order);
                    assertEquals(orderId, order.id());
                    assertEquals(correlationId, order.correlationId());
                })
                .verifyComplete();

        // Verificar que se publicó el evento correcto
        verify(eventPublisher).publishEvent(
                eventCaptor.capture(),
                eq("createOrder"),
                eq(EventTopics.ORDER_CREATED.getTopic()));

        OrderEvent capturedEvent = eventCaptor.getValue();
        assertEquals(OrderCreatedEvent.class, capturedEvent.getClass());
        assertEquals(orderId, capturedEvent.getOrderId());
        assertEquals(correlationId, capturedEvent.getCorrelationId());
    }

    @Test
    void testExecuteStep_Success() {
        // Arrange
        SagaStep step = SagaStep.builder()
                .name("testStep")
                .topic("test.topic")
                .stepType(SagaStepType.GENERIC_STEP) // Usar el tipo genérico
                .action(() -> Mono.empty())
                .compensation(() -> Mono.empty())
                .successEvent(eventId -> new OrderCreatedEvent(orderId, correlationId, eventId, externalReference, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .externalReference(externalReference)
                .build();

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeStep(step))
                .expectNextCount(1)
                .verifyComplete();

        // Verificar que se publicó el evento
        verify(eventPublisher).publishEvent(
                any(OrderEvent.class),
                eq(step.getName()),
                eq(step.getTopic()));
    }

    @Test
    void testExecuteOrderSaga_Success() {
        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeOrderSaga(quantity, amount))
                .expectNextCount(1)
                .verifyComplete();

        // Verificar que se generaron los IDs necesarios
        verify(idGenerator).generateOrderId();
        verify(idGenerator).generateCorrelationId();
        verify(idGenerator).generateEventId();
    }

    @Test
    void testPublishFailedEvent() {
        // Arrange
        OrderFailedEvent failedEvent = new OrderFailedEvent(
                orderId,
                correlationId,
                eventId,
                SagaStepType.FAILED_EVENT,
                "Test failure",
                externalReference);

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.publishFailedEvent(failedEvent))
                .verifyComplete();

        // Verificar que se publicó el evento de fallo
        verify(eventPublisher).publishEvent(
                eq(failedEvent),
                eq("failedEvent"),
                eq(EventTopics.ORDER_FAILED.getTopic()));
    }

    @Test
    void testPublishEvent() {
        // Arrange
        OrderEvent event = new OrderCreatedEvent(
                orderId, correlationId, eventId, externalReference, quantity);
        String stepName = "testStep";
        String topic = "test.topic";

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.publishEvent(event, stepName, topic))
                .expectNextCount(1)
                .verifyComplete();

        // Verificar que se publicó el evento
        verify(eventPublisher).publishEvent(
                eq(event),
                eq(stepName),
                eq(topic));
    }

    @Test
    void testCreateFailedEvent() {
        // Arrange
        String reason = "Test failure reason";

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.createFailedEvent(reason, externalReference))
                .verifyComplete();

        // Verificar que se publicó el evento de fallo
        verify(eventPublisher).publishEvent(
                any(OrderFailedEvent.class),
                eq("failedEvent"),
                eq(EventTopics.ORDER_FAILED.getTopic()));
    }
}