package com.example.order.service.unit;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStateMachine;
import com.example.order.domain.OrderStatus;
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
import java.util.EnumSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Prueba unitaria para SagaOrchestratorAtMostOnceImplV2.
 * Versión migrada que usa OrderStateMachineService en lugar de OrderStateMachine directamente.
 */
@ActiveProfiles("unit")
class SagaOrchestratorAtMostOnceV2UnitTest {

    // Sistema bajo prueba
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
    private OrderStateMachineService stateMachineService;

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
        stateMachineService = mock(OrderStateMachineService.class);

        // Configuración básica de mocks
        setupBasicMocks();
        setupStateMachineServiceMocks();

        // Crear el sistema bajo prueba
        sagaOrchestrator = new SagaOrchestratorAtMostOnceImplV2(
                transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                inventoryService,
                compensationManager,
                eventRepository,
                stateMachineService
        );
    }

    private void setupBasicMocks() {
        // Mock transactional operator
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Mock ID generation
        when(idGenerator.generateOrderId()).thenReturn(orderId);
        when(idGenerator.generateCorrelationId()).thenReturn(correlationId);
        when(idGenerator.generateEventId()).thenReturn(eventId);
        when(idGenerator.generateExternalReference()).thenReturn(externalReference);

        // Mock resilience manager
        when(resilienceManager.applyResilience(anyString()))
                .thenReturn(Function.identity());
        when(resilienceManager.applyResilience(any(com.example.order.config.CircuitBreakerCategory.class)))
                .thenReturn(Function.identity());

        // Mock event publisher
        OrderEvent mockEvent = mock(OrderEvent.class);
        EventPublishOutcome<OrderEvent> outcome = EventPublishOutcome.success(mockEvent);
        when(eventPublisher.publishEvent(any(OrderEvent.class), anyString(), anyString()))
                .thenReturn(Mono.just(outcome));

        // Mock inventory service
        when(inventoryService.reserveStock(anyLong(), anyInt())).thenReturn(Mono.empty());

        // Mock event repository
        when(eventRepository.isEventProcessed(anyString())).thenReturn(Mono.just(false));
        when(eventRepository.findOrderById(anyLong()))
                .thenReturn(Mono.just(new Order(orderId, OrderStatus.ORDER_PENDING, correlationId)));
        when(eventRepository.saveOrderData(anyLong(), anyString(), anyString(), any(OrderEvent.class)))
                .thenReturn(Mono.empty());
        when(eventRepository.updateOrderStatus(anyLong(), any(OrderStatus.class), anyString()))
                .thenAnswer(invocation -> {
                    Long id = invocation.getArgument(0);
                    OrderStatus status = invocation.getArgument(1);
                    String corrId = invocation.getArgument(2);
                    return Mono.just(new Order(id, status, corrId));
                });
        when(eventRepository.insertStatusAuditLog(anyLong(), any(OrderStatus.class), anyString()))
                .thenReturn(Mono.empty());
        when(eventRepository.saveEventHistory(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(eventRepository.recordSagaFailure(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(eventRepository.recordStepFailure(anyString(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(Mono.empty());
        when(eventRepository.insertCompensationLog(anyString(), anyLong(), anyString(), anyString(),
                any(OrderStatus.class))).thenReturn(Mono.empty());
    }

    private void setupStateMachineServiceMocks() {
        // Usar una instancia real de OrderStateMachine en lugar de mock
        OrderStateMachine realStateMachine = new OrderStateMachine(orderId, OrderStatus.ORDER_UNKNOWN);

        when(stateMachineService.createForOrder(anyLong(), any(OrderStatus.class)))
                .thenReturn(realStateMachine);

        when(stateMachineService.isValidTransition(any(OrderStatus.class), any(OrderStatus.class)))
                .thenReturn(true);

        when(stateMachineService.getValidNextStates(any(OrderStatus.class)))
                .thenReturn(EnumSet.of(OrderStatus.ORDER_PROCESSING, OrderStatus.ORDER_COMPLETED));

        when(stateMachineService.getTopicForTransition(any(OrderStatus.class), any(OrderStatus.class)))
                .thenReturn("order-topic");
    }


    @Test
    void testCreateOrder_Success() {
        when(stateMachineService.getTopicForTransition(OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_CREATED))
                .thenReturn("order-created");
        when(eventRepository.isEventProcessed(anyString()))
                .thenReturn(Mono.just(false));

        StepVerifier.create(sagaOrchestrator.createOrder(orderId, correlationId, eventId, externalReference, quantity))
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.correlationId().equals(correlationId))
                .verifyComplete();

        verify(eventRepository).saveOrderData(eq(orderId), eq(correlationId), eq(eventId), any(OrderEvent.class));
        verify(eventPublisher).publishEvent(any(OrderEvent.class), eq("createOrder"), eq("order-created"));
    }

    @Test
    void testExecuteStep_Success() {
        SagaStep step = SagaStep.builder()
                .name("testStep")
                .topic("test.topic")
                .stepType(SagaStepType.GENERIC_STEP)
                .action(() -> Mono.empty())
                .successEvent(eventId -> new OrderCreatedEvent(orderId, correlationId, eventId, externalReference, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .externalReference(externalReference)
                .build();

        StepVerifier.create(sagaOrchestrator.executeStep(step))
                .expectNextCount(1)
                .verifyComplete();

        verify(eventPublisher).publishEvent(any(OrderEvent.class), eq("testStep"), eq("test.topic"));
    }




    @Test
    void testCreateFailedEvent() {
        // Arrange
        String reason = "Test failure reason";

        // Act & Assert
        StepVerifier.create(sagaOrchestrator.createFailedEvent(reason, externalReference))
                .verifyComplete();

        // Verify event was published
        verify(eventPublisher).publishEvent(any(OrderFailedEvent.class), eq("failedEvent"), eq("order-topic"));

        // Verify interaction with EventRepository
        verify(eventRepository, atLeastOnce()).saveEventHistory(anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString());

        // Verify interaction with OrderStateMachineService
        verify(stateMachineService).getTopicForTransition(eq(OrderStatus.ORDER_UNKNOWN), eq(OrderStatus.ORDER_FAILED));
    }


    @Test
    void testExecuteOrderSaga_ValidationErrors() {
        // Test invalid quantity
        StepVerifier.create(sagaOrchestrator.executeOrderSaga(-1, 100.0))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().equals("Quantity must be positive"))
                .verify();

        // Test invalid amount
        StepVerifier.create(sagaOrchestrator.executeOrderSaga(5, -1))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().equals("Amount must be positive"))
                .verify();
    }

    // ✅ TEST UNITARIO 4: Solo testear idempotencia
    @Test
    void testExecuteOrderSaga_AlreadyProcessed() {
        when(eventRepository.isEventProcessed(anyString()))
                .thenReturn(Mono.just(true));
        when(eventRepository.findOrderById(orderId))
                .thenReturn(Mono.just(new Order(orderId, OrderStatus.ORDER_PENDING, correlationId)));

        StepVerifier.create(sagaOrchestrator.executeOrderSaga(quantity, amount))
                .expectNextMatches(order -> order.id().equals(orderId))
                .verifyComplete();

        verify(eventRepository).isEventProcessed(anyString());
        verify(eventRepository).findOrderById(orderId);
        // NO debe crear nueva orden
        verify(eventRepository, never()).saveOrderData(anyLong(), anyString(), anyString(), any());
    }

    // ✅ TEST UNITARIO 5: Solo testear publishFailedEvent con estado correcto
    @Test
    void testPublishFailedEvent_WithCorrectState() {
        OrderFailedEvent failedEvent = new OrderFailedEvent(
                orderId, correlationId, eventId, SagaStepType.FAILED_EVENT, "Test failure", externalReference);

        // CORREGIDO: Mockear el estado que realmente se usa
        when(eventRepository.findOrderById(orderId))
                .thenReturn(Mono.just(new Order(orderId, OrderStatus.ORDER_PENDING, correlationId)));
        when(stateMachineService.getTopicForTransition(OrderStatus.ORDER_PENDING, OrderStatus.ORDER_FAILED))
                .thenReturn("order-failed");

        StepVerifier.create(sagaOrchestrator.publishFailedEvent(failedEvent))
                .verifyComplete();

        verify(eventPublisher).publishEvent(eq(failedEvent), eq("failedEvent"), eq("order-failed"));
        verify(stateMachineService).getTopicForTransition(OrderStatus.ORDER_PENDING, OrderStatus.ORDER_FAILED);
    }

    // ✅ TEST UNITARIO 6: Solo testear cleanup una sola vez
    @Test
    void testStateMachineCleanup_OnlyOnce() {
        // Mock para que saga complete exitosamente
        when(inventoryService.reserveStock(anyLong(), anyInt()))
                .thenReturn(Mono.empty());
        when(eventRepository.isEventProcessed(anyString()))
                .thenReturn(Mono.just(false));

        StepVerifier.create(sagaOrchestrator.executeOrderSaga(quantity, amount))
                .expectNextCount(1)
                .verifyComplete();

        // El cleanup debe ejecutarse EXACTAMENTE una vez
        verify(stateMachineService, times(1)).removeForOrder(orderId);
    }

    // ✅ TEST UNITARIO 7: Solo testear manejo de error específico
    @Test
    void testExecuteStep_WithSpecificError() {
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

        StepVerifier.create(sagaOrchestrator.executeStep(step))
                .expectError(RuntimeException.class)
                .verify();

        // Verificar manejo de error
        verify(compensationManager).executeCompensation(eq(step));
        verify(eventRepository).recordStepFailure(anyString(), eq(orderId), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }


    @Test
    void testCreateOrder_ValidationErrors() {
        // Test null parameters
        StepVerifier.create(sagaOrchestrator.createOrder(null, correlationId, eventId, externalReference, quantity))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().contains("cannot be null"))
                .verify();
    }

    @Test
    void testExecuteStep_ValidationErrors() {
        // Test null step
        StepVerifier.create(sagaOrchestrator.executeStep(null))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException &&
                        e.getMessage().equals("SagaStep cannot be null"))
                .verify();

        // Test step with missing required fields
        SagaStep invalidStep = SagaStep.builder()
                .name("invalidStep")
                .topic("test.topic")
                .stepType(SagaStepType.GENERIC_STEP)
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                // Missing action and successEvent
                .build();

        StepVerifier.create(sagaOrchestrator.executeStep(invalidStep))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().equals("SagaStep has missing required fields"))
                .verify();
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
    void testTopicSelection_WithStateMachineService() {
        // Arrange
        String customTopic = "custom.order.topic";
        when(stateMachineService.getTopicForTransition(eq(OrderStatus.ORDER_UNKNOWN), eq(OrderStatus.ORDER_CREATED)))
                .thenReturn(customTopic);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);

        // Act
        StepVerifier.create(sagaOrchestrator.createOrder(orderId, correlationId, eventId, externalReference, quantity))
                .expectNextCount(1)
                .verifyComplete();

        // Assert
        verify(eventPublisher).publishEvent(any(OrderEvent.class), eq("createOrder"), topicCaptor.capture());
        assertEquals(customTopic, topicCaptor.getValue());
        verify(stateMachineService).getTopicForTransition(eq(OrderStatus.ORDER_UNKNOWN), eq(OrderStatus.ORDER_CREATED));
    }

    // ✅ TEST UNITARIO 8: Solo testear initialize
    @Test
    void testInitialize() {
        SagaOrchestratorAtMostOnceImplV2 concreteOrchestrator = (SagaOrchestratorAtMostOnceImplV2) sagaOrchestrator;

        assertDoesNotThrow(() -> concreteOrchestrator.initialize());

        // Verificar que las métricas se registraron (opcional)
        // No necesario verificar detalles internos de métricas
    }
}