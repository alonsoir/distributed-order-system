package com.example.order.repository;

import com.example.order.domain.DeliveryMode;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.events.OrderEvent;

import com.example.order.repository.events.EventHistoryRepository;
import com.example.order.repository.events.ProcessedEventRepository;
import com.example.order.repository.orders.OrderRepository;
import com.example.order.repository.saga.SagaFailureRepository;
import com.example.order.repository.transactions.TransactionLockRepository;
import com.example.order.service.SagaOrchestrator; // Import interface
import com.example.order.actuator.StrategyConfigurationManager; // Added import
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier; // Import Qualifier
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration; // <<< ADD IMPORT
import org.springframework.boot.test.mock.mockito.MockBean; // Import MockBean
import org.springframework.context.annotation.Bean; // <<< ADD IMPORT
import org.springframework.context.annotation.Primary; // <<< ADD IMPORT
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.Mockito; // <<< ADD IMPORT

/**
 * Test de integración para CompositeEventRepository que verifica su funcionamiento
 * con los flujos de saga y orquestadores reales.
 */
@SpringBootTest
@ActiveProfiles("integration-test") // Changed from "test" to "integration-test"
// @Import(CompositeEventRepository.class) // Importar explícitamente la clase bajo prueba -- REMOVED
public class CompositeEventRepositorySagaIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public OrderRepository primaryMockOrderRepository() {
            return Mockito.mock(OrderRepository.class);
        }
    }

    @Autowired
    private CompositeEventRepository compositeEventRepository;

    @MockBean // Changed from @Mock to @MockBean
    private ProcessedEventRepository processedEventRepository;

    //vvv REMOVE THIS MOCK FOR OrderRepositoryImpl vvv
    // @MockBean // Changed from @Mock to @MockBean
    // private com.example.order.repository.orders.OrderRepositoryImpl orderRepositoryImpl; // Changed to OrderRepositoryImpl
    //^^^ REMOVE THIS MOCK FOR OrderRepositoryImpl ^^^

    // Use the @Primary @Bean from TestConfig instead of marking this @MockBean as primary
    @MockBean
    private OrderRepository orderRepository; // <<< USE THE INTERFACE TYPE

    @MockBean // Changed from @Mock to @MockBean
    private SagaFailureRepository sagaFailureRepository;

    @MockBean // Changed from @Mock to @MockBean
    private EventHistoryRepository eventHistoryRepository;

    @MockBean // Changed from @Mock to @MockBean
    private TransactionLockRepository transactionLockRepository;

    @MockBean // Changed from @Mock to @MockBean and type to interface
    @Qualifier("sagaOrchestratorImpl2")
    private SagaOrchestrator sagaOrchestratorAtMostOnce;

    @MockBean // Changed from @Mock to @MockBean and type to interface
    @Qualifier("atLeastOnce")
    private SagaOrchestrator sagaOrchestratorAtLeastOnce;

    @MockBean
    private StrategyConfigurationManager strategyConfigurationManager;

    @Test
    @DisplayName("Verifica flujo AT_MOST_ONCE con idempotencia")
    public void testAtMostOnceIdempotence() {
        // Configuramos un escenario donde un evento ya ha sido procesado
        String eventId = "unique-event-id-123";
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        // Configurar el mock para que indique que el evento ya está procesado
        when(processedEventRepository.isEventProcessed(eventId, deliveryMode))
                .thenReturn(Mono.just(true));

        // Verificamos que el comportamiento es correcto
        StepVerifier.create(compositeEventRepository.isEventProcessed(eventId, deliveryMode))
                .expectNext(true)
                .verifyComplete();

        // Verificar que se consultó una sola vez
        verify(processedEventRepository, times(1)).isEventProcessed(eventId, deliveryMode);

        // Ahora verificamos que checkAndMarkEventAsProcessed devuelve false para un evento ya procesado
        when(processedEventRepository.checkAndMarkEventAsProcessed(eventId, deliveryMode))
                .thenReturn(Mono.just(false));

        StepVerifier.create(compositeEventRepository.checkAndMarkEventAsProcessed(eventId, deliveryMode))
                .expectNext(false)
                .verifyComplete();

        verify(processedEventRepository, times(1)).checkAndMarkEventAsProcessed(eventId, deliveryMode);
    }

    @Test
    @DisplayName("Verifica flujo AT_LEAST_ONCE con idempotencia")
    public void testAtLeastOnceIdempotence() {
        // Configurar un escenario donde un evento puede ser procesado múltiples veces
        String eventId = "at-least-once-event-456";
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        // Primera verificación: el evento no está procesado
        when(processedEventRepository.isEventProcessed(eventId, deliveryMode))
                .thenReturn(Mono.just(false));

        // Verificamos que el comportamiento es correcto
        StepVerifier.create(compositeEventRepository.isEventProcessed(eventId, deliveryMode))
                .expectNext(false)
                .verifyComplete();

        // Marcamos el evento como procesado
        when(processedEventRepository.markEventAsProcessed(eventId, deliveryMode))
                .thenReturn(Mono.empty());

        StepVerifier.create(compositeEventRepository.markEventAsProcessed(eventId, deliveryMode))
                .verifyComplete();

        // Ahora cambiamos el comportamiento para simular que ya fue procesado
        when(processedEventRepository.isEventProcessed(eventId, deliveryMode))
                .thenReturn(Mono.just(true));

        // Verificamos nuevamente
        StepVerifier.create(compositeEventRepository.isEventProcessed(eventId, deliveryMode))
                .expectNext(true)
                .verifyComplete();

        // Verificar las llamadas
        verify(processedEventRepository, times(2)).isEventProcessed(eventId, deliveryMode);
        verify(processedEventRepository, times(1)).markEventAsProcessed(eventId, deliveryMode);
    }

    @Test
    @DisplayName("Verifica flujo completo de creación de orden con manejo de errores")
    public void testCompleteOrderCreationFlowWithErrorHandling() {
        // Preparar datos de prueba
        Long orderId = 789L;
        String correlationId = "saga-corr-789";
        String eventId = "event-order-789";

        // Crear un evento de orden para el test
        OrderEvent orderEvent = mock(OrderEvent.class);
        when(orderEvent.getOrderId()).thenReturn(orderId);
        when(orderEvent.getCorrelationId()).thenReturn(correlationId);
        when(orderEvent.getEventId()).thenReturn(eventId);
        when(orderEvent.getType()).thenReturn(OrderStatus.ORDER_CREATED);

        // Mock de la orden
        Order mockOrder = mock(Order.class);
        when(mockOrder.id()).thenReturn(orderId);
        when(mockOrder.status()).thenReturn(OrderStatus.ORDER_PENDING);

        // Configurar comportamiento para el flujo completo
        // 1. Verificar si el evento ya está procesado (no lo está)
        when(processedEventRepository.checkAndMarkEventAsProcessed(eventId, DeliveryMode.AT_MOST_ONCE))
                .thenReturn(Mono.just(true));

        // 2. Guardar datos de la orden
        when(orderRepository.saveOrderData( // <<< Use new 'orderRepository' mock
                eq(orderId),
                eq(correlationId),
                eq(eventId),
                any(OrderEvent.class),
                eq(DeliveryMode.AT_MOST_ONCE)))
                .thenReturn(Mono.empty());

        // 3. Buscar la orden
        when(orderRepository.findOrderById(orderId)) // <<< Use new 'orderRepository' mock
                .thenReturn(Mono.just(mockOrder));

        // 4. Actualizar estado
        when(orderRepository.updateOrderStatus(orderId, OrderStatus.ORDER_COMPLETED, correlationId)) // <<< Use new 'orderRepository' mock
                .thenReturn(Mono.just(mockOrder));

        // 5. Insertar log de auditoría
        when(orderRepository.insertStatusAuditLog(orderId, OrderStatus.ORDER_COMPLETED, correlationId)) // <<< Use new 'orderRepository' mock
                .thenReturn(Mono.empty());

        // 6. Guardar historial del evento
        when(eventHistoryRepository.saveEventHistory(
                eq(eventId),
                eq(correlationId),
                eq(orderId),
                anyString(),
                anyString(),
                anyString(),
                eq(DeliveryMode.AT_MOST_ONCE)))
                .thenReturn(Mono.empty());

        // Ejecutar flujo: primero verificamos y marcamos el evento
        StepVerifier.create(compositeEventRepository.checkAndMarkEventAsProcessed(eventId, DeliveryMode.AT_MOST_ONCE))
                .expectNext(true)
                .verifyComplete();

        // Luego guardamos los datos de la orden
        StepVerifier.create(compositeEventRepository.saveOrderData(orderId, correlationId, eventId, orderEvent, DeliveryMode.AT_MOST_ONCE))
                .verifyComplete();

        // Buscamos la orden
        StepVerifier.create(compositeEventRepository.findOrderById(orderId))
                .expectNext(mockOrder)
                .verifyComplete();

        // Actualizamos el estado
        StepVerifier.create(compositeEventRepository.updateOrderStatus(orderId, OrderStatus.ORDER_COMPLETED, correlationId))
                .expectNext(mockOrder)
                .verifyComplete();

        // Insertamos log de auditoría
        StepVerifier.create(compositeEventRepository.insertStatusAuditLog(orderId, OrderStatus.ORDER_COMPLETED, correlationId))
                .verifyComplete();

        // Guardamos el historial del evento
        StepVerifier.create(compositeEventRepository.saveEventHistory(
                        eventId,
                        correlationId,
                        orderId,
                        "ORDER_CREATED",
                        "PROCESS",
                        "SUCCESS",
                        DeliveryMode.AT_MOST_ONCE))
                .verifyComplete();

        // Verificar llamadas a los repositorios
        verify(processedEventRepository).checkAndMarkEventAsProcessed(eventId, DeliveryMode.AT_MOST_ONCE);
        verify(orderRepository).saveOrderData( // <<< Use new 'orderRepository' mock
                eq(orderId),
                eq(correlationId),
                eq(eventId),
                any(OrderEvent.class),
                eq(DeliveryMode.AT_MOST_ONCE));
        verify(orderRepository).findOrderById(orderId); // <<< Use new 'orderRepository' mock
        verify(orderRepository).updateOrderStatus(orderId, OrderStatus.ORDER_COMPLETED, correlationId); // <<< Use new 'orderRepository' mock
        verify(orderRepository).insertStatusAuditLog(orderId, OrderStatus.ORDER_COMPLETED, correlationId); // <<< Use new 'orderRepository' mock
        verify(eventHistoryRepository).saveEventHistory(
                eq(eventId),
                eq(correlationId),
                eq(orderId),
                anyString(),
                anyString(),
                anyString(),
                eq(DeliveryMode.AT_MOST_ONCE));
    }

    @Test
    @DisplayName("Verifica manejo de errores en caso de fallos durante procesamiento")
    public void testErrorHandlingDuringProcessing() {
        // Preparar datos de prueba
        Long orderId = 999L;
        String correlationId = "saga-corr-999";
        String eventId = "event-order-999";
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        // Simular un evento de reserva de stock que falla
        OrderEvent orderEvent = mock(OrderEvent.class);
        when(orderEvent.getEventId()).thenReturn(eventId);
        when(orderEvent.getOrderId()).thenReturn(orderId);
        when(orderEvent.getCorrelationId()).thenReturn(correlationId);
        when(orderEvent.getType()).thenReturn(OrderStatus.STOCK_RESERVED);

        // Configurar comportamiento de error
        RuntimeException stockError = new RuntimeException("Insufficient stock available");

        // El evento no ha sido procesado todavía
        when(processedEventRepository.checkAndMarkEventAsProcessed(eventId, deliveryMode))
                .thenReturn(Mono.just(true));

        // La operación de guardar datos falla
        when(orderRepository.saveOrderData(eq(orderId), eq(correlationId), eq(eventId), any(OrderEvent.class), eq(deliveryMode))) // <<< Use new 'orderRepository' mock
                .thenReturn(Mono.error(stockError));

        // Configurar registro de errores
        when(sagaFailureRepository.recordStepFailure(
                eq("reserveStock"),
                eq(orderId),
                eq(correlationId),
                eq(eventId),
                anyString(),
                eq("RuntimeException"),
                eq("TECHNICAL")))
                .thenReturn(Mono.empty());

        // Configurar actualización de estado a fallido
        Order failedOrder = mock(Order.class);
        when(failedOrder.id()).thenReturn(orderId);
        when(failedOrder.status()).thenReturn(OrderStatus.ORDER_FAILED);

        when(orderRepository.updateOrderStatus(orderId, OrderStatus.ORDER_FAILED, correlationId)) // <<< Use new 'orderRepository' mock
                .thenReturn(Mono.just(failedOrder));

        // Ejecutar flujo con error
        // Primero verificamos y marcamos el evento
        StepVerifier.create(compositeEventRepository.checkAndMarkEventAsProcessed(eventId, deliveryMode))
                .expectNext(true)
                .verifyComplete();

        // Luego intentamos guardar los datos, lo que debe fallar
        StepVerifier.create(compositeEventRepository.saveOrderData(orderId, correlationId, eventId, orderEvent, deliveryMode))
                .expectErrorMatches(error -> error.equals(stockError))
                .verify();

        // Verificar las llamadas realizadas
        verify(processedEventRepository).checkAndMarkEventAsProcessed(eventId, deliveryMode);
        verify(orderRepository).saveOrderData(eq(orderId), eq(correlationId), eq(eventId), any(OrderEvent.class), eq(deliveryMode)); // <<< Use new 'orderRepository' mock

        // Ahora simulamos el registro del error
        when(sagaFailureRepository.recordSagaFailure(
                eq(orderId),
                eq(correlationId),
                contains("Insufficient stock"),
                eq("RuntimeException"),
                eq(deliveryMode)))
                .thenReturn(Mono.empty());

        // Registramos el error
        StepVerifier.create(compositeEventRepository.recordSagaFailure(
                        orderId,
                        correlationId,
                        "Insufficient stock available",
                        "RuntimeException",
                        deliveryMode))
                .verifyComplete();

        // Y actualizamos el estado a fallido
        StepVerifier.create(compositeEventRepository.updateOrderStatus(orderId, OrderStatus.ORDER_FAILED, correlationId))
                .expectNext(failedOrder)
                .verifyComplete();

        // Verificar las llamadas
        verify(sagaFailureRepository).recordSagaFailure(
                eq(orderId),
                eq(correlationId),
                anyString(),
                eq("RuntimeException"),
                eq(deliveryMode));
        verify(orderRepository).updateOrderStatus(orderId, OrderStatus.ORDER_FAILED, correlationId); // <<< Use new 'orderRepository' mock
    }

    @Test
    @DisplayName("Verifica las operaciones de bloqueo transaccional")
    public void testTransactionLockOperations() {
        // Datos de prueba
        String resourceId = "order:123";
        String correlationId = "lock-corr-123";
        int timeoutSeconds = 30;

        // Configurar adquisición exitosa del bloqueo
        when(transactionLockRepository.acquireTransactionLock(resourceId, correlationId, timeoutSeconds))
                .thenReturn(Mono.just(true));

        // Ejecutar y verificar adquisición
        StepVerifier.create(compositeEventRepository.acquireTransactionLock(resourceId, correlationId, timeoutSeconds))
                .expectNext(true)
                .verifyComplete();

        // Ahora configurar liberación del bloqueo
        when(transactionLockRepository.releaseTransactionLock(resourceId, correlationId))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar liberación
        StepVerifier.create(compositeEventRepository.releaseTransactionLock(resourceId, correlationId))
                .verifyComplete();

        // Verificar llamadas
        verify(transactionLockRepository).acquireTransactionLock(resourceId, correlationId, timeoutSeconds);
        verify(transactionLockRepository).releaseTransactionLock(resourceId, correlationId);
    }

    @Test
    @DisplayName("Verifica recuperación ante fallos durante adquisición de bloqueo")
    public void testRecoveryFromLockAcquisitionFailure() {
        // Datos de prueba
        String resourceId = "order:456";
        String correlationId = "lock-corr-456";
        int timeoutSeconds = 30;

        // Configurar fallo inicial y luego éxito (simulando reintento)
        when(transactionLockRepository.acquireTransactionLock(resourceId, correlationId, timeoutSeconds))
                .thenReturn(Mono.error(new RuntimeException("Lock DB unavailable")))
                .thenReturn(Mono.just(true));

        // Ejecutar - este test depende de la implementación de reintentos en CompositeEventRepository
        // Si no hay reintentos implementados, fallará
        StepVerifier.create(compositeEventRepository.acquireTransactionLock(resourceId, correlationId, timeoutSeconds))
                .expectNext(true)
                .verifyComplete();

        // Verificar llamadas múltiples al repositorio (al menos 2 debido al reintento)
        verify(transactionLockRepository, atLeast(2)).acquireTransactionLock(resourceId, correlationId, timeoutSeconds);
    }
}