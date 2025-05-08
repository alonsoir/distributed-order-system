package com.example.order.events;

/**
 * Interfaz que representa un evento de orden en el sistema.
 */
public interface OrderEvent {

    /**
     * @return El ID único del evento
     */
    String getEventId();

    /**
     * @return El ID de correlación que permite agrupar eventos relacionados
     */
    String getCorrelationId();

    /**
     * @return El ID de la orden asociada al evento
     */
    Long getOrderId();

    /**
     * @return El tipo de evento
     */
    OrderEventType getType();

    /**
     * @return Representación JSON del evento
     */
    String toJson();
}