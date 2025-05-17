package com.example.order.repository;

import com.example.order.domain.DeliveryMode;
import com.example.order.domain.Order;
import com.example.order.events.OrderEvent;
import com.example.order.repository.events.EventHistoryRepository;
import com.example.order.repository.events.ProcessedEventRepository;
import com.example.order.repository.orders.OrderRepository;
import com.example.order.repository.saga.SagaFailureRepository;
import com.example.order.repository.transactions.TransactionLockRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Implementación compuesta del EventRepository que delega en repositorios especializados
 */
@Primary
@Repository
@Validated
public class CompositeEventRepository implements EventRepository {

    private final ProcessedEventRepository processedEventRepository;
    private final OrderRepository orderRepository;
    private final SagaFailureRepository sagaFailureRepository;
    private final EventHistoryRepository eventHistoryRepository;
    private final TransactionLockRepository transactionLockRepository;

    public CompositeEventRepository(
            ProcessedEventRepository processedEventRepository,
            OrderRepository orderRepository,
            SagaFailureRepository sagaFailureRepository,
            EventHistoryRepository eventHistoryRepository,
            TransactionLockRepository transactionLockRepository) {
        this.processedEventRepository = processedEventRepository;
        this.orderRepository = orderRepository;
        this.sagaFailureRepository = sagaFailureRepository;
        this.eventHistoryRepository = eventHistoryRepository;
        this.transactionLockRepository = transactionLockRepository;
    }

    @Override
    public Mono<Boolean> isEventProcessed(String eventId) {
        return processedEventRepository.isEventProcessed(eventId);
    }

    @Override
    public Mono<Boolean> isEventProcessed(String eventId, DeliveryMode deliveryMode) {
        return processedEventRepository.isEventProcessed(eventId, deliveryMode);
    }

    @Override
    public Mono<Void> markEventAsProcessed(String eventId) {
        return processedEventRepository.markEventAsProcessed(eventId);
    }

    @Override
    public Mono<Void> markEventAsProcessed(String eventId, DeliveryMode deliveryMode) {
        return processedEventRepository.markEventAsProcessed(eventId, deliveryMode);
    }

    @Override
    public Mono<Boolean> checkAndMarkEventAsProcessed(String eventId, DeliveryMode deliveryMode) {
        return processedEventRepository.checkAndMarkEventAsProcessed(eventId, deliveryMode);
    }

    @Override
    public Mono<Order> findOrderById(Long orderId) {
        return orderRepository.findOrderById(orderId);
    }

    @Override
    public Mono<Void> saveOrderData(Long orderId, String correlationId, String eventId, OrderEvent event) {
        return orderRepository.saveOrderData(orderId, correlationId, eventId, event);
    }

    @Override
    public Mono<Void> saveOrderData(Long orderId, String correlationId, String eventId, OrderEvent event, DeliveryMode deliveryMode) {
        return orderRepository.saveOrderData(orderId, correlationId, eventId, event, deliveryMode);
    }

    @Override
    public Mono<Order> updateOrderStatus(Long orderId, String status, String correlationId) {
        return orderRepository.updateOrderStatus(orderId, status, correlationId);
    }

    @Override
    public Mono<Void> insertStatusAuditLog(Long orderId, String status, String correlationId) {
        return orderRepository.insertStatusAuditLog(orderId, status, correlationId);
    }

    @Override
    public Mono<Void> insertCompensationLog(String stepName, Long orderId, String correlationId, String eventId, String status) {
        return sagaFailureRepository.insertCompensationLog(stepName, orderId, correlationId, eventId, status);
    }

    @Override
    public Mono<Void> recordStepFailure(String stepName, Long orderId, String correlationId, String eventId, String errorMessage, String errorType, String errorCategory) {
        return sagaFailureRepository.recordStepFailure(stepName, orderId, correlationId, eventId, errorMessage, errorType, errorCategory);
    }

    @Override
    public Mono<Void> recordSagaFailure(Long orderId, String correlationId, String errorMessage, String errorType, String errorCategory) {
        return sagaFailureRepository.recordSagaFailure(orderId, correlationId, errorMessage, errorType, errorCategory);
    }

    @Override
    public Mono<Void> recordSagaFailure(Long orderId, String correlationId, String errorMessage, String errorType, DeliveryMode deliveryMode) {
        return sagaFailureRepository.recordSagaFailure(orderId, correlationId, errorMessage, errorType, deliveryMode);
    }

    @Override
    public Mono<Void> saveEventHistory(String eventId, String correlationId, Long orderId, String eventType, String operation, String outcome) {
        return eventHistoryRepository.saveEventHistory(eventId, correlationId, orderId, eventType, operation, outcome);
    }

    @Override
    public Mono<Void> saveEventHistory(String eventId, String correlationId, Long orderId, String eventType, String operation, String outcome, DeliveryMode deliveryMode) {
        return eventHistoryRepository.saveEventHistory(eventId, correlationId, orderId, eventType, operation, outcome, deliveryMode);
    }

    @Override
    public Mono<Boolean> acquireTransactionLock(String resourceId, String correlationId, int timeoutSeconds) {
        return transactionLockRepository.acquireTransactionLock(resourceId, correlationId, timeoutSeconds);
    }

    @Override
    public Mono<Void> releaseTransactionLock(String resourceId, String correlationId) {
        return transactionLockRepository.releaseTransactionLock(resourceId, correlationId);
    }
}