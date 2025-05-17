package com.example.order.repository.base;

import com.example.order.domain.DeliveryMode;
import com.example.order.domain.Order;
import reactor.core.publisher.Mono;

/**
 * Interfaz para abstraer operaciones de base de datos
 * Facilita la futura migración a procedimientos almacenados
 */
public interface DatabaseOperations {
    // Operaciones de eventos procesados
    Mono<Boolean> isEventProcessedInDb(String eventId);
    Mono<Boolean> isEventProcessedWithDeliveryModeInDb(String eventId, DeliveryMode deliveryMode);
    Mono<Void> markEventAsProcessedInDb(String eventId);
    Mono<Void> markEventAsProcessedWithDeliveryModeInDb(String eventId, DeliveryMode deliveryMode);
    Mono<Boolean> checkAndMarkEventAsProcessedInDb(String eventId, DeliveryMode deliveryMode);

    // Operaciones de órdenes
    Mono<Order> findOrderByIdInDb(Long orderId);
    Mono<Void> insertOrderInDb(Long orderId, String status, String correlationId, DeliveryMode deliveryMode);
    Mono<Void> insertOutboxEventInDb(String eventType, String correlationId, String eventId, String payload, DeliveryMode deliveryMode);
    Mono<Void> updateOrderStatusInDb(Long orderId, String status);
    Mono<Void> insertStatusAuditLogInDb(Long orderId, String status, String correlationId);

    // Operaciones de fallos de saga
    Mono<Void> insertCompensationLogInDb(String stepName, Long orderId, String correlationId, String eventId, String status);
    Mono<Void> recordStepFailureInDb(String stepName, Long orderId, String correlationId, String eventId,
                                     String errorMessage, String errorType, String errorCategory);
    Mono<Void> recordSagaFailureInDb(Long orderId, String correlationId, String errorMessage,
                                     String errorType, DeliveryMode deliveryMode);

    // Operaciones de historial de eventos
    Mono<Void> saveEventHistoryInDb(String eventId, String correlationId, Long orderId, String eventType,
                                    String operation, String outcome, DeliveryMode deliveryMode);

    // Operaciones de bloqueo
    Mono<Void> cleanExpiredLocksInDb();
    Mono<Boolean> acquireLockInDb(String resourceId, String correlationId, String lockUuid,
                                  java.time.LocalDateTime expiresAt);
    Mono<Void> releaseLockInDb(String resourceId, String correlationId, String lockUuid);
}