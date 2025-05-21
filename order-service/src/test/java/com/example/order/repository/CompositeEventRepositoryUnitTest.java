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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import jakarta.validation.ConstraintViolationException;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios básicos para CompositeEventRepository.
 * Estos tests verifican principalmente que el repositorio compuesto
 * delega correctamente a los repositorios subyacentes.
 */
@ExtendWith(MockitoExtension.class)
class CompositeEventRepositoryUnitTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private SagaFailureRepository sagaFailureRepository;

    @Mock
    private EventHistoryRepository eventHistoryRepository;

    @Mock
    private TransactionLockRepository transactionLockRepository;

    @InjectMocks
    private CompositeEventRepository compositeEventRepository;

    // Timeout para tests
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(2);

    @Test
    @DisplayName("Validación: no debe permitir eventId nulo o vacío")
     void testValidationForNullOrEmptyEventId() {
        // Caso 1: eventId nulo - debería fallar con ConstraintViolationException
        StepVerifier.create(compositeEventRepository.isEventProcessed(null))
                .expectError(ConstraintViolationException.class)
                .verify(TEST_TIMEOUT);

        // Caso 2: eventId vacío - debería fallar con ConstraintViolationException
        StepVerifier.create(compositeEventRepository.isEventProcessed(""))
                .expectError(ConstraintViolationException.class)
                .verify(TEST_TIMEOUT);

        // Verificar que el repositorio subyacente nunca fue llamado con valores inválidos
        verify(processedEventRepository, never()).isEventProcessed(isNull());
        verify(processedEventRepository, never()).isEventProcessed(eq(""));
    }

    @Test
    @DisplayName("Validación: no debe permitir DeliveryMode nulo")
    void testValidationForNullDeliveryMode() {
        // Validación de @NotNull en DeliveryMode
        StepVerifier.create(compositeEventRepository.isEventProcessed("event-id", null))
                .expectError(ConstraintViolationException.class)
                .verify(TEST_TIMEOUT);

        // Verificar que el repositorio subyacente nunca fue llamado con DeliveryMode nulo
        verify(processedEventRepository, never()).isEventProcessed(anyString(), isNull());
    }

    @Test
    @DisplayName("Debe delegar correctamente la verificación de eventos procesados")
     void testDelegatesEventProcessedCheck() {
        // Configurar comportamiento normal
        when(processedEventRepository.isEventProcessed("test-event"))
                .thenReturn(Mono.just(true));

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.isEventProcessed("test-event"))
                .expectNext(true)
                .verifyComplete();

        // Verificar que se delegó correctamente
        verify(processedEventRepository).isEventProcessed("test-event");
    }

    @Test
    @DisplayName("Debe delegar correctamente la verificación de eventos con modo de entrega")
     void testDelegatesEventProcessedCheckWithDeliveryMode() {
        // Configurar comportamiento normal
        when(processedEventRepository.isEventProcessed("test-event", DeliveryMode.AT_LEAST_ONCE))
                .thenReturn(Mono.just(true));

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.isEventProcessed("test-event", DeliveryMode.AT_LEAST_ONCE))
                .expectNext(true)
                .verifyComplete();

        // Verificar que se delegó correctamente
        verify(processedEventRepository).isEventProcessed("test-event", DeliveryMode.AT_LEAST_ONCE);
    }

    @Test
    @DisplayName("Debe delegar correctamente el marcar eventos como procesados")
     void testDelegatesMarkEventAsProcessed() {
        // Configurar comportamiento normal
        when(processedEventRepository.markEventAsProcessed("test-event"))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.markEventAsProcessed("test-event"))
                .verifyComplete();

        // Verificar que se delegó correctamente
        verify(processedEventRepository).markEventAsProcessed("test-event");
    }

    @Test
    @DisplayName("Debe delegar correctamente marcar eventos con modo de entrega")
     void testDelegatesMarkEventAsProcessedWithDeliveryMode() {
        // Configurar comportamiento normal
        when(processedEventRepository.markEventAsProcessed("test-event", DeliveryMode.AT_MOST_ONCE))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.markEventAsProcessed("test-event", DeliveryMode.AT_MOST_ONCE))
                .verifyComplete();

        // Verificar que se delegó correctamente
        verify(processedEventRepository).markEventAsProcessed("test-event", DeliveryMode.AT_MOST_ONCE);
    }

    @Test
    @DisplayName("Debe delegar correctamente verificar y marcar eventos como procesados")
     void testDelegatesCheckAndMarkEventAsProcessed() {
        // Configurar comportamiento normal
        when(processedEventRepository.checkAndMarkEventAsProcessed("test-event", DeliveryMode.AT_LEAST_ONCE))
                .thenReturn(Mono.just(true));

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.checkAndMarkEventAsProcessed("test-event", DeliveryMode.AT_LEAST_ONCE))
                .expectNext(true)
                .verifyComplete();

        // Verificar que se delegó correctamente
        verify(processedEventRepository).checkAndMarkEventAsProcessed("test-event", DeliveryMode.AT_LEAST_ONCE);
    }

    @Test
    @DisplayName("Debe delegar correctamente la búsqueda de órdenes por ID")
     void testDelegatesFindOrderById() {
        // Crear orden simulada para el test
        Order mockOrder = new Order();
        mockOrder.setId(123L);

        // Configurar comportamiento
        when(orderRepository.findOrderById(123L))
                .thenReturn(Mono.just(mockOrder));

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.findOrderById(123L))
                .expectNext(mockOrder)
                .verifyComplete();

        // Verificar delegación correcta
        verify(orderRepository).findOrderById(123L);
    }

    @Test
    @DisplayName("Debe delegar correctamente guardar datos de orden")
     void testDelegatesSaveOrderData() {
        // Configurar mock
        OrderEvent mockEvent = mock(OrderEvent.class);
        when(orderRepository.saveOrderData(eq(123L), eq("correlation-id"), eq("event-id"), same(mockEvent)))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.saveOrderData(123L, "correlation-id", "event-id", mockEvent))
                .verifyComplete();

        // Verificar delegación correcta
        verify(orderRepository).saveOrderData(123L, "correlation-id", "event-id", mockEvent);
    }

    @Test
    @DisplayName("Debe delegar correctamente guardar datos de orden con modo de entrega")
     void testDelegatesSaveOrderDataWithDeliveryMode() {
        // Configurar mock
        OrderEvent mockEvent = mock(OrderEvent.class);
        when(orderRepository.saveOrderData(eq(123L), eq("correlation-id"), eq("event-id"), same(mockEvent), eq(DeliveryMode.AT_MOST_ONCE)))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.saveOrderData(123L, "correlation-id", "event-id", mockEvent, DeliveryMode.AT_MOST_ONCE))
                .verifyComplete();

        // Verificar delegación correcta
        verify(orderRepository).saveOrderData(123L, "correlation-id", "event-id", mockEvent, DeliveryMode.AT_MOST_ONCE);
    }

    @Test
    @DisplayName("Debe delegar correctamente actualizar estado de orden")
     void testDelegatesUpdateOrderStatus() {
        // Crear orden simulada para el test
        Order mockOrder = new Order();
        mockOrder.setId(123L);
        mockOrder.setStatus(OrderStatus.ORDER_COMPLETED);

        // Configurar comportamiento
        when(orderRepository.updateOrderStatus(123L, OrderStatus.ORDER_COMPLETED, "correlation-id"))
                .thenReturn(Mono.just(mockOrder));

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.updateOrderStatus(123L, OrderStatus.ORDER_COMPLETED, "correlation-id"))
                .expectNext(mockOrder)
                .verifyComplete();

        // Verificar delegación correcta
        verify(orderRepository).updateOrderStatus(123L, OrderStatus.ORDER_COMPLETED, "correlation-id");
    }

    @Test
    @DisplayName("Debe delegar correctamente insertar logs de auditoría de estado")
     void testDelegatesInsertStatusAuditLog() {
        // Configurar comportamiento
        when(orderRepository.insertStatusAuditLog(123L, OrderStatus.ORDER_COMPLETED, "correlation-id"))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.insertStatusAuditLog(123L, OrderStatus.ORDER_COMPLETED, "correlation-id"))
                .verifyComplete();

        // Verificar delegación correcta
        verify(orderRepository).insertStatusAuditLog(123L, OrderStatus.ORDER_COMPLETED, "correlation-id");
    }

    @Test
    @DisplayName("Debe delegar correctamente insertar logs de compensación")
     void testDelegatesInsertCompensationLog() {
        // Configurar comportamiento
        when(sagaFailureRepository.insertCompensationLog("PAYMENT", 123L, "correlation-id",
                "event-id",
                OrderStatus.ORDER_COMPENSATED))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.insertCompensationLog("PAYMENT", 123L,
                        "correlation-id",
                        "event-id",
                        OrderStatus.ORDER_COMPENSATED))
                .verifyComplete();

        // Verificar delegación correcta
        verify(sagaFailureRepository).insertCompensationLog("PAYMENT", 123L,
                "correlation-id",
                "event-id",
                OrderStatus.ORDER_COMPENSATED);
    }

    @Test
    @DisplayName("Debe delegar correctamente registrar fallos de paso")
     void testDelegatesRecordStepFailure() {
        // Configurar comportamiento
        when(sagaFailureRepository.recordStepFailure(
                "PAYMENT", 123L, "correlation-id", "event-id",
                "Payment failed", "PAYMENT_ERROR", "BUSINESS_ERROR"))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.recordStepFailure(
                        "PAYMENT", 123L, "correlation-id", "event-id",
                        "Payment failed", "PAYMENT_ERROR", "BUSINESS_ERROR"))
                .verifyComplete();

        // Verificar delegación correcta
        verify(sagaFailureRepository).recordStepFailure(
                "PAYMENT", 123L, "correlation-id", "event-id",
                "Payment failed", "PAYMENT_ERROR", "BUSINESS_ERROR");
    }

    @Test
    @DisplayName("Debe delegar correctamente registrar fallos de saga")
     void testDelegatesRecordSagaFailure() {
        // Configurar comportamiento
        when(sagaFailureRepository.recordSagaFailure(
                123L, "correlation-id", "Saga failed", "SAGA_ERROR", "SYSTEM_ERROR"))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.recordSagaFailure(
                        123L, "correlation-id", "Saga failed", "SAGA_ERROR", "SYSTEM_ERROR"))
                .verifyComplete();

        // Verificar delegación correcta
        verify(sagaFailureRepository).recordSagaFailure(
                123L, "correlation-id", "Saga failed", "SAGA_ERROR", "SYSTEM_ERROR");
    }

    @Test
    @DisplayName("Debe delegar correctamente registrar fallos de saga con modo de entrega")
     void testDelegatesRecordSagaFailureWithDeliveryMode() {
        // Configurar comportamiento
        when(sagaFailureRepository.recordSagaFailure(
                123L, "correlation-id", "Saga failed", "SAGA_ERROR", DeliveryMode.AT_LEAST_ONCE))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.recordSagaFailure(
                        123L, "correlation-id", "Saga failed", "SAGA_ERROR", DeliveryMode.AT_LEAST_ONCE))
                .verifyComplete();

        // Verificar delegación correcta
        verify(sagaFailureRepository).recordSagaFailure(
                123L, "correlation-id", "Saga failed", "SAGA_ERROR", DeliveryMode.AT_LEAST_ONCE);
    }

    @Test
    @DisplayName("Debe delegar correctamente guardar historial de eventos")
     void testDelegatesSaveEventHistory() {
        // Configurar comportamiento
        when(eventHistoryRepository.saveEventHistory(
                "event-id", "correlation-id", 123L, "ORDER_CREATED", "CREATE", "SUCCESS"))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.saveEventHistory(
                        "event-id", "correlation-id", 123L, "ORDER_CREATED", "CREATE", "SUCCESS"))
                .verifyComplete();

        // Verificar delegación correcta
        verify(eventHistoryRepository).saveEventHistory(
                "event-id", "correlation-id", 123L, "ORDER_CREATED", "CREATE", "SUCCESS");
    }

    @Test
    @DisplayName("Debe delegar correctamente guardar historial de eventos con modo de entrega")
     void testDelegatesSaveEventHistoryWithDeliveryMode() {
        // Configurar comportamiento
        when(eventHistoryRepository.saveEventHistory(
                "event-id", "correlation-id", 123L, "ORDER_CREATED", "CREATE", "SUCCESS", DeliveryMode.AT_MOST_ONCE))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar
        StepVerifier.create(compositeEventRepository.saveEventHistory(
                        "event-id", "correlation-id", 123L, "ORDER_CREATED", "CREATE", "SUCCESS", DeliveryMode.AT_MOST_ONCE))
                .verifyComplete();

        // Verificar delegación correcta
        verify(eventHistoryRepository).saveEventHistory(
                "event-id", "correlation-id", 123L, "ORDER_CREATED", "CREATE", "SUCCESS", DeliveryMode.AT_MOST_ONCE);
    }

    @Test
    @DisplayName("Debe delegar correctamente las operaciones de bloqueo de transacción")
     void testDelegatesTransactionLockOperations() {
        // Configurar comportamiento para adquirir
        when(transactionLockRepository.acquireTransactionLock("resource-id", "corr-id", 10))
                .thenReturn(Mono.just(true));

        // Configurar comportamiento para liberar
        when(transactionLockRepository.releaseTransactionLock("resource-id", "corr-id"))
                .thenReturn(Mono.empty());

        // Ejecutar y verificar adquisición
        StepVerifier.create(compositeEventRepository.acquireTransactionLock("resource-id", "corr-id", 10))
                .expectNext(true)
                .verifyComplete();

        // Ejecutar y verificar liberación
        StepVerifier.create(compositeEventRepository.releaseTransactionLock("resource-id", "corr-id"))
                .verifyComplete();

        // Verificar delegación correcta
        verify(transactionLockRepository).acquireTransactionLock("resource-id", "corr-id", 10);
        verify(transactionLockRepository).releaseTransactionLock("resource-id", "corr-id");
    }

    @Test
    @DisplayName("Debe rechazar IDs de recurso inválidos en operaciones de bloqueo")
     void testValidatesResourceId() {
        // Caso 1: resourceId nulo
        StepVerifier.create(compositeEventRepository.acquireTransactionLock(null, "corr-id", 10))
                .expectError(IllegalArgumentException.class)
                .verify(TEST_TIMEOUT);

        // Caso 2: resourceId vacío
        StepVerifier.create(compositeEventRepository.acquireTransactionLock("", "corr-id", 10))
                .expectError(IllegalArgumentException.class)
                .verify(TEST_TIMEOUT);

        // Verificar que nunca se llamó al repositorio subyacente
        verify(transactionLockRepository, never()).acquireTransactionLock(isNull(), anyString(), anyInt());
        verify(transactionLockRepository, never()).acquireTransactionLock(eq(""), anyString(), anyInt());
    }

    @Test
    @DisplayName("Debe rechazar IDs de correlación inválidos en operaciones de bloqueo")
     void testValidatesCorrelationId() {
        // Caso 1: correlationId nulo
        StepVerifier.create(compositeEventRepository.acquireTransactionLock("resource-id", null, 10))
                .expectError(IllegalArgumentException.class)
                .verify(TEST_TIMEOUT);

        // Caso 2: correlationId vacío
        StepVerifier.create(compositeEventRepository.acquireTransactionLock("resource-id", "", 10))
                .expectError(IllegalArgumentException.class)
                .verify(TEST_TIMEOUT);

        // Verificar que nunca se llamó al repositorio subyacente
        verify(transactionLockRepository, never()).acquireTransactionLock(anyString(), isNull(), anyInt());
        verify(transactionLockRepository, never()).acquireTransactionLock(anyString(), eq(""), anyInt());
    }
}