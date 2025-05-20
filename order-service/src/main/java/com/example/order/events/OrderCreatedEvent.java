package com.example.order.events;

import com.example.order.domain.OrderStatus;
import lombok.Getter;

/**
 * Evento que representa la creación de una orden
 */
@Getter
public class OrderCreatedEvent implements OrderEvent {
    private final Long orderId;
    private final String correlationId;
    private final String eventId;
    private final String externalReference;
    private final int quantity;

    public OrderCreatedEvent(Long orderId, String correlationId, String eventId,String externalReference, int quantity) {
        this.orderId = orderId;
        this.correlationId = correlationId;
        this.eventId = eventId;
        this.externalReference = externalReference;
        this.quantity = quantity;
    }

    @Override
    public OrderStatus getType() {
        return OrderStatus.ORDER_CREATED;
    }

    @Override
    public String toJson() {
        return "{\"orderId\":" + orderId +
                ",\"correlationId\":\"" + correlationId +
                "\",\"eventId\":\"" + eventId +
                "\",\"externalReference\":\"" + externalReference +
                "\",\"quantity\":" + quantity +
                "\",\"type\":\"" + getType().name() + "\"}";
    }

}