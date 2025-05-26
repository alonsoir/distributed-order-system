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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Implementación compuesta del EventRepository que delega en repositorios especializados.
 *
 * Esta clase implementa la interfaz EventRepository y extiende AbstractEventRepository
 * para obtener las funcionalidades de infraestructura (métricas, reintentos, circuit breakers).
 *
 * Responsabilidades:
 * - Implementar el contrato público de EventRepository
 * - Delegar operaciones a repositorios especializados
 * - Aplicar validaciones específicas donde sea necesario
 */
@Primary
@Repository
@Validated
public class CompositeEventRepository extends AbstractEventRepository implements EventRepository {

    private static final Logger log = LoggerFactory.getLogger(CompositeEventRepository.class);

    private final ProcessedEventRepository processedEventRepository;
    private final OrderRepository orderRepository;
    private final SagaFailureRepository sagaFailureRepository;
    private final EventHistoryRepository eventHistoryRepository;
    private final TransactionLockRepository transactionLockRepository;

    public CompositeEventRepository(
            MeterRegistry meterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ProcessedEventRepository processedEventRepository,
            OrderRepository orderRepository,
            SagaFailureRepository sagaFailureRepository,
            EventHistoryRepository eventHistoryRepository,
            TransactionLockRepository transactionLockRepository) {

        // Llamar al constructor padre PRIMERO para inicializar infraestructura
        super(meterRegistry, circuitBreakerRegistry);

        // Inicializar dependencias de negocio
        this.processedEventRepository = processedEventRepository;
        this.orderRepository = orderRepository;
        this.sagaFailureRepository = sagaFailureRepository;
        this.eventHistoryRepository = eventHistoryRepository;
        this.transactionLockRepository = transactionLockRepository;
    }

    // ===========================================
    // IMPLEMENTACIÓN DE LA INTERFAZ EventRepository
    // ===========================================

    @Override
    public Mono<OrderStatus> getOrderStatus(@NotNull Long orderId) {
        return executeOperation("getOrderStatus",
                orderRepository.getOrderStatus(orderId),
                this::isTransientError);
    }

    @Override
    public Mono<Boolean> isEventProcessed(@NotBlank String eventId) {
        return executeOperation("isEventProcessed",
                processedEventRepository.isEventProcessed(eventId),
                this::isTransientError);
    }

    @Override
    public Mono<Boolean> isEventProcessed(@NotBlank String eventId, @NotNull DeliveryMode deliveryMode) {
        return executeOperation("isEventProcessed",
                processedEventRepository.isEventProcessed(eventId, deliveryMode),
                this::isTransientError);
    }

    @Override
    public Mono<Void> markEventAsProcessed(@NotBlank String eventId) {
        return executeOperation("markEventAsProcessed",
                processedEventRepository.markEventAsProcessed(eventId),
                this::isTransientError);
    }

    @Override
    public Mono<Void> markEventAsProcessed(@NotBlank String eventId, @NotNull DeliveryMode deliveryMode) {
        return executeOperation("markEventAsProcessed",
                processedEventRepository.markEventAsProcessed(eventId, deliveryMode),
                this::isTransientError);
    }

    @Override
    public Mono<Boolean> checkAndMarkEventAsProcessed(@NotBlank String eventId, @NotNull DeliveryMode deliveryMode) {
        return executeOperation("checkAndMarkEventAsProcessed",
                processedEventRepository.checkAndMarkEventAsProcessed(eventId, deliveryMode),
                this::isTransientError);
    }

    @Override
    public Mono<Order> findOrderById(@NotNull Long orderId) {
        return executeOperation("findOrderById",
                orderRepository.findOrderById(orderId),
                this::isTransientError);
    }

    @Override
    public Mono<Void> saveOrderData(@NotNull Long orderId, @NotBlank String correlationId,
                                    @NotBlank String eventId, @NotNull OrderEvent event) {
        return executeOperation("saveOrderData",
                orderRepository.saveOrderData(orderId, correlationId, eventId, event),
                this::isTransientError);
    }

    @Override
    public Mono<Void> saveOrderData(@NotNull Long orderId, @NotBlank String correlationId,
                                    @NotBlank String eventId, @NotNull OrderEvent event,
                                    @NotNull DeliveryMode deliveryMode) {
        return executeOperation("saveOrderData",
                orderRepository.saveOrderData(orderId, correlationId, eventId, event, deliveryMode),
                this::isTransientError);
    }

    @Override
    public Mono<Order> updateOrderStatus(@NotNull Long orderId, @NotNull OrderStatus status,
                                         @NotBlank String correlationId) {
        return executeOperation("updateOrderStatus",
                orderRepository.updateOrderStatus(orderId, status, correlationId),
                this::isTransientError);
    }

    @Override
    public Mono<Void> insertStatusAuditLog(@NotNull Long orderId, @NotNull OrderStatus status,
                                           @NotBlank String correlationId) {
        return executeOperation("insertStatusAuditLog",
                orderRepository.insertStatusAuditLog(orderId, status, correlationId),
                this::isTransientError);
    }

    @Override
    public Mono<Void> insertCompensationLog(@NotBlank String stepName, @NotNull Long orderId,
                                            @NotBlank String correlationId, @NotBlank String eventId,
                                            @NotNull OrderStatus status) {
        return executeOperation("insertCompensationLog",
                sagaFailureRepository.insertCompensationLog(stepName, orderId, correlationId, eventId, status),
                this::isTransientError);
    }

    @Override
    public Mono<Void> recordStepFailure(@NotBlank String stepName, @NotNull Long orderId,
                                        @NotBlank String correlationId, @NotBlank String eventId,
                                        @NotBlank String errorMessage, @NotBlank String errorType,
                                        @NotBlank String errorCategory) {
        return executeOperation("recordStepFailure",
                sagaFailureRepository.recordStepFailure(stepName, orderId, correlationId, eventId,
                        errorMessage, errorType, errorCategory),
                this::isTransientError);
    }

    @Override
    public Mono<Void> recordSagaFailure(@NotNull Long orderId, @NotBlank String correlationId,
                                        @NotBlank String errorMessage, @NotBlank String errorType,
                                        @NotBlank String errorCategory) {
        return executeOperation("recordSagaFailure",
                sagaFailureRepository.recordSagaFailure(orderId, correlationId, errorMessage, errorType, errorCategory),
                this::isTransientError);
    }

    @Override
    public Mono<Void> recordSagaFailure(@NotNull Long orderId, @NotBlank String correlationId,
                                        @NotBlank String errorMessage, @NotBlank String errorType,
                                        @NotNull DeliveryMode deliveryMode) {
        return executeOperation("recordSagaFailure",
                sagaFailureRepository.recordSagaFailure(orderId, correlationId, errorMessage, errorType, deliveryMode),
                this::isTransientError);
    }

    @Override
    public Mono<Void> saveEventHistory(@NotBlank String eventId, @NotBlank String correlationId,
                                       @NotNull Long orderId, @NotBlank String eventType,
                                       @NotBlank String operation, @NotBlank String outcome) {
        return executeOperation("saveEventHistory",
                eventHistoryRepository.saveEventHistory(eventId, correlationId, orderId, eventType, operation, outcome),
                this::isTransientError);
    }

    @Override
    public Mono<Void> saveEventHistory(@NotBlank String eventId, @NotBlank String correlationId,
                                       @NotNull Long orderId, @NotBlank String eventType,
                                       @NotBlank String operation, @NotBlank String outcome,
                                       @NotNull DeliveryMode deliveryMode) {
        return executeOperation("saveEventHistory",
                eventHistoryRepository.saveEventHistory(eventId, correlationId, orderId, eventType,
                        operation, outcome, deliveryMode),
                this::isTransientError);
    }

    @Override
    public Mono<Boolean> acquireTransactionLock(String resourceId, String correlationId, int timeoutSeconds) {
        // Validaciones específicas para operaciones de bloqueo
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Resource ID cannot be null or empty"));
        }
        if (correlationId == null || correlationId.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Correlation ID cannot be null or empty"));
        }

        return executeOperation("acquireTransactionLock",
                transactionLockRepository.acquireTransactionLock(resourceId, correlationId, timeoutSeconds),
                this::isTransientError);
    }

    @Override
    public Mono<Void> releaseTransactionLock(String resourceId, String correlationId) {
        // Validaciones específicas para operaciones de bloqueo
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Resource ID cannot be null or empty"));
        }
        if (correlationId == null || correlationId.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Correlation ID cannot be null or empty"));
        }

        return executeOperation("releaseTransactionLock",
                transactionLockRepository.releaseTransactionLock(resourceId, correlationId),
                this::isTransientError);
    }

    // ===========================================
    // MÉTODOS DE CONVENIENCIA (OPCIONAL)
    // ===========================================

    /**
     * Método de conveniencia que utiliza la funcionalidad de la clase padre
     * para ejecutar operaciones con bloqueo transaccional.
     */
    protected <T> Mono<T> executeWithTransactionLock(String resourceId, String correlationId,
                                                     int timeoutSeconds, Mono<T> operation) {
        return executeWithLock(resourceId, correlationId, timeoutSeconds, operation,
                // Lock acquirer function
                resId -> corrId -> timeout -> acquireTransactionLock(resId, corrId, timeout),
                // Lock releaser function
                resId -> corrId -> releaseTransactionLock(resId, corrId));
    }
}