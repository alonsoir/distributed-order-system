package com.example.order.repository;

import com.example.order.domain.DeliveryMode;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.events.OrderEvent;
import reactor.core.publisher.Mono;

/**
 * Repositorio para operaciones relacionadas con eventos y órdenes en la base de datos.
 * Proporciona una capa de abstracción sobre el acceso a datos.
 */
public interface EventRepository {
    /**
     * Verifica si un evento ya ha sido procesado
     */
    Mono<Boolean> isEventProcessed(String eventId);

    /**
     * Verifica si un evento ya ha sido procesado con un modo de entrega específico
     */
    Mono<Boolean> isEventProcessed(String eventId, DeliveryMode deliveryMode);

    /**
     * Marca un evento como procesado
     */
    Mono<Void> markEventAsProcessed(String eventId);

    /**
     * Marca un evento como procesado con un modo de entrega específico
     */
    Mono<Void> markEventAsProcessed(String eventId, DeliveryMode deliveryMode);

    /**
     * Verifica y marca un evento como procesado en una sola operación atómica
     * para evitar condiciones de carrera
     */
    Mono<Boolean> checkAndMarkEventAsProcessed(String eventId, DeliveryMode deliveryMode);

    /**
     * Busca una orden por su ID
     */
    Mono<Order> findOrderById(Long orderId);

    /**
     * Guarda los datos de una nueva orden
     */
    Mono<Void> saveOrderData(Long orderId, String correlationId, String eventId, OrderEvent event);

    /**
     * Guarda los datos de una nueva orden con modo de entrega específico
     */
    Mono<Void> saveOrderData(Long orderId, String correlationId, String eventId, OrderEvent event, DeliveryMode deliveryMode);

    /**
     * Actualiza el estado de una orden
     */
    Mono<Order> updateOrderStatus(Long orderId, OrderStatus status, String correlationId);

    /**
     * Inserta un registro en el historial de estados de la orden
     */
    Mono<Void> insertStatusAuditLog(Long orderId, OrderStatus status, String correlationId);

    /**
     * Registra un fallo de compensación
     */
    Mono<Void> insertCompensationLog(String stepName, Long orderId, String correlationId, String eventId, OrderStatus status);

    /**
     * Registra un fallo de paso en la saga
     */
    Mono<Void> recordStepFailure(String stepName, Long orderId, String correlationId, String eventId, String errorMessage, String errorType, String errorCategory);

    /**
     * Registra un fallo completo de saga
     */
    Mono<Void> recordSagaFailure(Long orderId, String correlationId, String errorMessage, String errorType, String errorCategory);

    /**
     * Registra un fallo completo de saga con modo de entrega específico
     */
    Mono<Void> recordSagaFailure(Long orderId, String correlationId, String errorMessage, String errorType, DeliveryMode deliveryMode);

    /**
     * Guarda historial de eventos para auditoría
     */
    Mono<Void> saveEventHistory(String eventId, String correlationId, Long orderId, String eventType, String operation, String outcome);

    /**
     * Guarda historial de eventos para auditoría con modo de entrega específico
     */
    Mono<Void> saveEventHistory(String eventId, String correlationId, Long orderId, String eventType, String operation, String outcome, DeliveryMode deliveryMode);

    /**
     * Adquiere un bloqueo de transacción para un recurso
     */
    Mono<Boolean> acquireTransactionLock(String resourceId, String correlationId, int timeoutSeconds);

    /**
     * Libera un bloqueo de transacción para un recurso
     */
    Mono<Void> releaseTransactionLock(String resourceId, String correlationId);
}