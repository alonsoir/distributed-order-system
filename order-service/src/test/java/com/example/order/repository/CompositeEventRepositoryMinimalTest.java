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
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test unitario minimalista para CompositeEventRepository.
 * Se enfoca en verificar que las operaciones se delegan correctamente
 * a los repositorios subyacentes sin la complejidad de métricas.
 */
@ExtendWith(MockitoExtension.class)
class CompositeEventRepositoryMinimalTest {

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

    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CompositeEventRepository compositeEventRepository;

    @BeforeEach
    void setUp() {
        // Usar SimpleMeterRegistry real en lugar de mock para evitar problemas
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        // Configurar el mock de CircuitBreakerRegistry con lenient para evitar stubbings innecesarios
        setupCircuitBreakerRegistryMock();

        compositeEventRepository = new CompositeEventRepository(
                meterRegistry,
                circuitBreakerRegistry,
                processedEventRepository,
                orderRepository,
                sagaFailureRepository,
                eventHistoryRepository,
                transactionLockRepository
        );
    }

    /**
     * Configura el mock de CircuitBreakerRegistry para evitar errores de NullPointer
     * Usa lenient() para evitar errores de unnecessary stubbing
     */
    private void setupCircuitBreakerRegistryMock() {
        // Mock para CircuitBreaker
        io.github.resilience4j.circuitbreaker.CircuitBreaker mockCircuitBreaker =
                mock(io.github.resilience4j.circuitbreaker.CircuitBreaker.class);

        // Configurar comportamiento básico del CircuitBreaker con lenient para evitar unnecessary stubbing
        lenient().when(mockCircuitBreaker.tryAcquirePermission()).thenReturn(true);
        lenient().when(mockCircuitBreaker.getName()).thenReturn("test-circuit-breaker");

        // Configurar el CircuitBreakerRegistry para devolver el mock
        lenient().when(circuitBreakerRegistry.circuitBreaker(anyString())).thenReturn(mockCircuitBreaker);
    }

    @Test
    @DisplayName("Debe delegar correctamente la verificación de eventos procesados")
    void shouldDelegateIsEventProcessed() {
        // Given
        when(processedEventRepository.isEventProcessed("test-event"))
                .thenReturn(Mono.just(true));

        // When & Then
        StepVerifier.create(compositeEventRepository.isEventProcessed("test-event"))
                .expectNext(true)
                .verifyComplete();

        verify(processedEventRepository).isEventProcessed("test-event");
    }

    @Test
    @DisplayName("Debe delegar correctamente la verificación de eventos con modo de entrega")
    void shouldDelegateIsEventProcessedWithDeliveryMode() {
        // Given
        when(processedEventRepository.isEventProcessed("test-event", DeliveryMode.AT_LEAST_ONCE))
                .thenReturn(Mono.just(false));

        // When & Then
        StepVerifier.create(compositeEventRepository.isEventProcessed("test-event", DeliveryMode.AT_LEAST_ONCE))
                .expectNext(false)
                .verifyComplete();

        verify(processedEventRepository).isEventProcessed("test-event", DeliveryMode.AT_LEAST_ONCE);
    }

    @Test
    @DisplayName("Debe delegar correctamente marcar eventos como procesados")
    void shouldDelegateMarkEventAsProcessed() {
        // Given
        when(processedEventRepository.markEventAsProcessed("test-event"))
                .thenReturn(Mono.empty());

        // When & Then
        StepVerifier.create(compositeEventRepository.markEventAsProcessed("test-event"))
                .verifyComplete();

        verify(processedEventRepository).markEventAsProcessed("test-event");
    }

    @Test
    @DisplayName("Debe delegar correctamente la búsqueda de órdenes por ID")
    void shouldDelegateFindOrderById() {
        // Given
        Order mockOrder = new Order();
        mockOrder.setId(123L);
        when(orderRepository.findOrderById(123L))
                .thenReturn(Mono.just(mockOrder));

        // When & Then
        StepVerifier.create(compositeEventRepository.findOrderById(123L))
                .expectNext(mockOrder)
                .verifyComplete();

        verify(orderRepository).findOrderById(123L);
    }

    @Test
    @DisplayName("Debe delegar correctamente guardar datos de orden")
    void shouldDelegateSaveOrderData() {
        // Given
        OrderEvent mockEvent = mock(OrderEvent.class);
        when(orderRepository.saveOrderData(eq(123L), eq("correlation-id"), eq("event-id"), same(mockEvent)))
                .thenReturn(Mono.empty());

        // When & Then
        StepVerifier.create(compositeEventRepository.saveOrderData(123L, "correlation-id", "event-id", mockEvent))
                .verifyComplete();

        verify(orderRepository).saveOrderData(123L, "correlation-id", "event-id", mockEvent);
    }

    @Test
    @DisplayName("Debe delegar correctamente actualizar estado de orden")
    void shouldDelegateUpdateOrderStatus() {
        // Given
        Order mockOrder = new Order();
        mockOrder.setId(123L);
        mockOrder.setStatus(OrderStatus.ORDER_COMPLETED);
        when(orderRepository.updateOrderStatus(123L, OrderStatus.ORDER_COMPLETED, "correlation-id"))
                .thenReturn(Mono.just(mockOrder));

        // When & Then
        StepVerifier.create(compositeEventRepository.updateOrderStatus(123L, OrderStatus.ORDER_COMPLETED, "correlation-id"))
                .expectNext(mockOrder)
                .verifyComplete();

        verify(orderRepository).updateOrderStatus(123L, OrderStatus.ORDER_COMPLETED, "correlation-id");
    }

    @Test
    @DisplayName("Debe delegar correctamente las operaciones de bloqueo de transacción")
    void shouldDelegateTransactionLockOperations() {
        // Given
        when(transactionLockRepository.acquireTransactionLock("resource-id", "corr-id", 10))
                .thenReturn(Mono.just(true));
        when(transactionLockRepository.releaseTransactionLock("resource-id", "corr-id"))
                .thenReturn(Mono.empty());

        // When & Then - Acquire
        StepVerifier.create(compositeEventRepository.acquireTransactionLock("resource-id", "corr-id", 10))
                .expectNext(true)
                .verifyComplete();

        // When & Then - Release
        StepVerifier.create(compositeEventRepository.releaseTransactionLock("resource-id", "corr-id"))
                .verifyComplete();

        verify(transactionLockRepository).acquireTransactionLock("resource-id", "corr-id", 10);
        verify(transactionLockRepository).releaseTransactionLock("resource-id", "corr-id");
    }

    @Test
    @DisplayName("Debe rechazar IDs de recurso inválidos en operaciones de bloqueo")
    void shouldValidateResourceId() {
        // When & Then - null resourceId
        StepVerifier.create(compositeEventRepository.acquireTransactionLock(null, "corr-id", 10))
                .expectError(IllegalArgumentException.class)
                .verify();

        // When & Then - empty resourceId
        StepVerifier.create(compositeEventRepository.acquireTransactionLock("", "corr-id", 10))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Verify no calls to underlying repository
        verify(transactionLockRepository, never()).acquireTransactionLock(isNull(), anyString(), anyInt());
        verify(transactionLockRepository, never()).acquireTransactionLock(eq(""), anyString(), anyInt());
    }

    @Test
    @DisplayName("Debe rechazar IDs de correlación inválidos en operaciones de bloqueo")
    void shouldValidateCorrelationId() {
        // When & Then - null correlationId
        StepVerifier.create(compositeEventRepository.acquireTransactionLock("resource-id", null, 10))
                .expectError(IllegalArgumentException.class)
                .verify();

        // When & Then - empty correlationId
        StepVerifier.create(compositeEventRepository.acquireTransactionLock("resource-id", "", 10))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Verify no calls to underlying repository
        verify(transactionLockRepository, never()).acquireTransactionLock(anyString(), isNull(), anyInt());
        verify(transactionLockRepository, never()).acquireTransactionLock(anyString(), eq(""), anyInt());
    }

    @Test
    @DisplayName("Debe delegar correctamente guardar historial de eventos")
    void shouldDelegateSaveEventHistory() {
        // Given
        when(eventHistoryRepository.saveEventHistory(
                "event-id", "correlation-id", 123L, "ORDER_CREATED", "CREATE", "SUCCESS"))
                .thenReturn(Mono.empty());

        // When & Then
        StepVerifier.create(compositeEventRepository.saveEventHistory(
                        "event-id", "correlation-id", 123L, "ORDER_CREATED", "CREATE", "SUCCESS"))
                .verifyComplete();

        verify(eventHistoryRepository).saveEventHistory(
                "event-id", "correlation-id", 123L, "ORDER_CREATED", "CREATE", "SUCCESS");
    }

    @Test
    @DisplayName("Debe delegar correctamente registrar fallos de saga")
    void shouldDelegateRecordSagaFailure() {
        // Given
        when(sagaFailureRepository.recordSagaFailure(
                123L, "correlation-id", "Saga failed", "SAGA_ERROR", "SYSTEM_ERROR"))
                .thenReturn(Mono.empty());

        // When & Then
        StepVerifier.create(compositeEventRepository.recordSagaFailure(
                        123L, "correlation-id", "Saga failed", "SAGA_ERROR", "SYSTEM_ERROR"))
                .verifyComplete();

        verify(sagaFailureRepository).recordSagaFailure(
                123L, "correlation-id", "Saga failed", "SAGA_ERROR", "SYSTEM_ERROR");
    }
}