package com.example.order.service.unit;

import com.example.order.domain.Order;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderCreatedEvent;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.model.SagaStep;
import com.example.order.model.SagaStepType;
import com.example.order.repository.EventRepository;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.*;
import com.example.order.service.v2.SagaOrchestratorAtMostOnceImplV2;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * Prueba unitaria para SagaOrchestratorAtMostOnceImplV2.
 * Se centra exclusivamente en los métodos públicos definidos en la interfaz SagaOrchestrator.
 * Esta versión utiliza EventRepository en lugar de DatabaseClient.
 */
@ActiveProfiles("unit")
class SagaOrchestratorAtMostOnceV2UnitTest {

    // Sistema bajo prueba referenciado a través de la interfaz
    private SagaOrchestrator sagaOrchestrator;

    // Mocks de dependencias
    private TransactionalOperator transactionalOperator;
    private MeterRegistry meterRegistry;
    private IdGenerator idGenerator;
    private ResilienceManager resilienceManager;
    private InventoryService inventoryService;
    private EventPublisher eventPublisher;
    private EventRepository eventRepository;
    private CompensationManager compensationManager;

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
        transactionalOperator = mock(TransactionalOperator.class);
        meterRegistry = new SimpleMeterRegistry();
        idGenerator = mock(IdGenerator.class);
        resilienceManager = mock(ResilienceManager.class);
        inventoryService = mock(InventoryService.class);
        eventPublisher = mock(EventPublisher.class);
        eventRepository = mock(EventRepository.class);
        compensationManager = mock(CompensationManager.class);

        // Mock response cuando se utiliza transactional
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Mock generación de IDs
        when(idGenerator.generateOrderId()).thenReturn(orderId);
        when(idGenerator.generateCorrelationId()).thenReturn(correlationId);
        when(idGenerator.generateEventId()).thenReturn(eventId);
        when(idGenerator.generateExternalReference()).thenReturn(externalReference);

        // Mock respuesta de ResilienceManager
        when(resilienceManager.applyResilience(anyString()))
                .thenReturn(Function.identity());
        when(resilienceManager.applyResilience(any(com.example.order.config.CircuitBreakerCategory.class)))
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

        // Crear el sistema bajo prueba
        sagaOrchestrator = new SagaOrchestratorAtMostOnceImplV2(
                transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                inventoryService,
                compensationManager,
                eventRepository
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

        // Verificar interacción con EventRepository
        verify(eventRepository).saveOrderData(eq(orderId), eq(correlationId), eq(eventId), any(OrderEvent.class));
        verify(eventRepository, atLeastOnce()).saveEventHistory(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString());
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

        // Verificar interacción con EventRepository
        verify(eventRepository, atLeastOnce()).saveEventHistory(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void testExecuteOrderSaga_Success() {
        // Mock para que updateOrderStatus devuelva una orden completada
        when(eventRepository.updateOrderStatus(anyLong(), eq("completed"), anyString()))
                .thenReturn(Mono.just(new Order(orderId, "completed", correlationId)));

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeOrderSaga(quantity, amount))
                .assertNext(order -> {
                    assertNotNull(order);
                    assertEquals(orderId, order.id());
                    assertEquals("completed", order.status());
                    assertEquals(correlationId, order.correlationId());
                })
                .verifyComplete();

        // Verificar que se generaron los IDs necesarios
        verify(idGenerator).generateOrderId();
        verify(idGenerator).generateCorrelationId();
        verify(idGenerator).generateEventId();

        // Verificar interacción con EventRepository
        verify(eventRepository).updateOrderStatus(anyLong(), eq("completed"), anyString());
        verify(eventRepository, atLeastOnce()).saveEventHistory(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString());
        verify(eventRepository).saveOrderData(eq(orderId), eq(correlationId), eq(eventId), any(OrderEvent.class));
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

        // Verificar interacción con EventRepository
        verify(eventRepository, atLeastOnce()).saveEventHistory(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString());
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

        // Verificar interacción con EventRepository
        verify(eventRepository, atLeastOnce()).saveEventHistory(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString());
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

        // Verificar interacción con EventRepository
        verify(eventRepository, atLeastOnce()).saveEventHistory(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void testExecuteOrderSaga_AlreadyProcessed() {
        // Simular que el evento ya fue procesado
        when(eventRepository.isEventProcessed(anyString())).thenReturn(Mono.just(true));

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeOrderSaga(quantity, amount))
                .assertNext(order -> {
                    assertNotNull(order);
                    assertEquals(orderId, order.id());
                    assertEquals("pending", order.status());
                    assertEquals(correlationId, order.correlationId());
                })
                .verifyComplete();

        // Verificar que se consultó si el evento estaba procesado
        verify(eventRepository).isEventProcessed(anyString());

        // Verificar que se recuperó la orden existente
        verify(eventRepository).findOrderById(orderId);

        // Verificar que no se creó una nueva orden
        verify(eventRepository, never()).saveOrderData(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void testExecuteOrderSaga_Invalid_Quantity() {
        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeOrderSaga(-1, amount))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().equals("Quantity must be positive"))
                .verify();
    }

    @Test
    void testExecuteOrderSaga_Invalid_Amount() {
        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeOrderSaga(quantity, -1))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().equals("Amount must be positive"))
                .verify();
    }

    @Test
    void testCreateOrder_EventAlreadyProcessed() {
        // Simular que el evento ya fue procesado
        when(eventRepository.isEventProcessed(anyString())).thenReturn(Mono.just(true));

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.createOrder(orderId, correlationId, eventId, externalReference, quantity))
                .assertNext(order -> {
                    assertNotNull(order);
                    assertEquals(orderId, order.id());
                    assertEquals("pending", order.status());
                    assertEquals(correlationId, order.correlationId());
                })
                .verifyComplete();

        // Verificar que se consultó si el evento estaba procesado
        verify(eventRepository).isEventProcessed(anyString());

        // Verificar que se recuperó la orden existente
        verify(eventRepository).findOrderById(orderId);

        // Verificar que no se insertó la nueva orden
        verify(eventRepository, never()).saveOrderData(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void testExecuteStep_Error() {
        // Arrange
        RuntimeException expectedError = new RuntimeException("Test error");

        SagaStep step = SagaStep.builder()
                .name("errorStep")
                .topic("test.topic")
                .stepType(SagaStepType.GENERIC_STEP)
                .action(() -> Mono.error(expectedError))
                .compensation(() -> Mono.empty())
                .successEvent(eventId -> new OrderCreatedEvent(orderId, correlationId, eventId, externalReference, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .externalReference(externalReference)
                .build();

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeStep(step))
                .expectError(RuntimeException.class)
                .verify();

        // Verificar que se registró el error
        verify(eventRepository).recordStepFailure(
                eq("errorStep"),
                eq(orderId),
                eq(correlationId),
                eq(eventId),
                anyString(),
                eq("RuntimeException"),
                anyString());

        // Verificar que se ejecutó la compensación
        verify(compensationManager).executeCompensation(eq(step));

        // Verificar log de compensación
        verify(eventRepository).insertCompensationLog(
                eq("errorStep"),
                eq(orderId),
                eq(correlationId),
                eq(eventId),
                anyString());
    }

    @Test
    void testExecuteOrderSaga_InventoryServiceFailure() {
        // Arrange
        RuntimeException expectedError = new RuntimeException("Inventory service error");
        when(inventoryService.reserveStock(anyLong(), anyInt()))
                .thenReturn(Mono.error(expectedError));

        // Mock para que updateOrderStatus con status "failed" funcione
        when(eventRepository.updateOrderStatus(anyLong(), eq("failed"), anyString()))
                .thenReturn(Mono.just(new Order(orderId, "failed", correlationId)));

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeOrderSaga(quantity, amount))
                .assertNext(order -> {
                    assertNotNull(order);
                    assertEquals(orderId, order.id());
                    assertEquals("failed", order.status());
                    assertEquals(correlationId, order.correlationId());
                })
                .verifyComplete();

        // Verificar que se actualizó el estado a fallido
        verify(eventRepository).updateOrderStatus(eq(orderId), eq("failed"), eq(correlationId));

        // Verificar que se registró el fallo
        verify(eventRepository).recordSagaFailure(
                eq(orderId),
                eq(correlationId),
                contains("Inventory service error"),
                eq("RuntimeException"),
                anyString());
    }

    @Test
    void testExecuteOrderSaga_MissingFields_Error() {
        // Simulate missing IDs
        when(idGenerator.generateOrderId()).thenReturn(null);

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeOrderSaga(quantity, amount))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException &&
                        ex.getMessage().equals("Failed to generate required IDs"))
                .verify();
    }

    @Test
    void testCreateOrder_MissingParameters() {
        // Act & Assert
        StepVerifier.create(sagaOrchestrator.createOrder(null, correlationId, eventId, externalReference, quantity))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().contains("cannot be null"))
                .verify();
    }

    @Test
    void testExecuteOrderSaga_PendingOrderNotProceedingToNextStep() {
        // Simular un estado de orden diferente a "pending" después de crearla
        Order nonPendingOrder = new Order(orderId, "another_status", correlationId);

        // Mock para que updateOrderStatus devuelva una orden con estado no "pending"
        when(eventRepository.updateOrderStatus(anyLong(), anyString(), anyString()))
                .thenReturn(Mono.just(nonPendingOrder));

        // Mock para que findExistingOrder también devuelva una orden con estado no "pending"
        when(eventRepository.findOrderById(anyLong()))
                .thenReturn(Mono.just(nonPendingOrder));

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeOrderSaga(quantity, amount))
                .assertNext(order -> {
                    assertNotNull(order);
                    assertEquals(orderId, order.id());
                    assertEquals("another_status", order.status());
                    assertEquals(correlationId, order.correlationId());
                })
                .verifyComplete();

        // Verificar que NO se intentó reservar stock
        verify(inventoryService, never()).reserveStock(anyLong(), anyInt());
    }

    @Test
    void testPublishFailedEvent_NullEvent() {
        // Act & Assert
        StepVerifier.create(sagaOrchestrator.publishFailedEvent(null))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().equals("Failed event cannot be null"))
                .verify();
    }

    @Test
    void testCreateFailedEvent_NullReason() {
        // Act & Assert
        StepVerifier.create(sagaOrchestrator.createFailedEvent(null, externalReference))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().equals("Failure reason cannot be null"))
                .verify();
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
    void testExecuteStep_NullActionOrRequiredFields() {
        // Prepare a step with missing required fields
        SagaStep invalidStep = SagaStep.builder()
                .name("invalidStep")
                // Omitting action, which is required
                .topic("test.topic")
                .stepType(SagaStepType.GENERIC_STEP)
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.executeStep(invalidStep))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().equals("SagaStep has missing required fields"))
                .verify();
    }

    @Test
    void testInitialize() {
        // Get instance of SagaOrchestratorAtMostOnceImplV2 to call initialize method
        SagaOrchestratorAtMostOnceImplV2 concreteOrchestrator = (SagaOrchestratorAtMostOnceImplV2) sagaOrchestrator;

        // Act - just verify it doesn't throw an exception
        concreteOrchestrator.initialize();

        // Usando SimpleMeterRegistry no podemos hacer muchas verificaciones
        // pero al menos confirmamos que el método se ejecuta sin errores
    }
}