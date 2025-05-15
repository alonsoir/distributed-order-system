package com.example.order.repository;

import com.example.order.domain.Order;
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
     * Marca un evento como procesado
     */
    Mono<Void> markEventAsProcessed(String eventId);

    /**
     * Busca una orden por su ID
     */
    Mono<Order> findOrderById(Long orderId);

    /**
     * Guarda los datos de una nueva orden
     */
    Mono<Void> saveOrderData(Long orderId, String correlationId, String eventId, OrderEvent event);

    /**
     * Actualiza el estado de una orden
     */
    Mono<Order> updateOrderStatus(Long orderId, String status, String correlationId);

    /**
     * Inserta un registro en el historial de estados de la orden
     */
    Mono<Void> insertStatusAuditLog(Long orderId, String status, String correlationId);

    /**
     * Registra un fallo de compensación
     */
    Mono<Void> insertCompensationLog(String stepName, Long orderId, String correlationId, String eventId, String status);

    /**
     * Registra un fallo de paso en la saga
     */
    Mono<Void> recordStepFailure(String stepName, Long orderId, String correlationId, String eventId, String errorMessage, String errorType, String errorCategory);

    /**
     * Registra un fallo completo de saga
     */
    Mono<Void> recordSagaFailure(Long orderId, String correlationId, String errorMessage, String errorType, String errorCategory);

    /**
     * Guarda historial de eventos para auditoría
     */
    Mono<Void> saveEventHistory(String eventId, String correlationId, Long orderId, String eventType, String operation, String outcome);
}