package com.example.order.events;

import lombok.Getter;

@Getter
public class OrderCreatedEvent implements OrderEvent {
    private final Long orderId;
    private final String correlationId;
    private final String eventId;
    private final String status;

    public OrderCreatedEvent(Long orderId, String correlationId, String eventId, String status) {
        validate(orderId, correlationId, eventId);
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Status cannot be null or empty");
        }
        this.orderId = orderId;
        this.correlationId = correlationId;
        this.eventId = eventId;
        this.status = status;
    }

    private static void validate(Long orderId, String correlationId, String eventId) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("OrderId cannot be null and must be positive");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("CorrelationId cannot be null or empty");
        }
        if (eventId == null || eventId.isBlank() ) {
            throw new IllegalArgumentException("EventId cannot be null and must be positive");
        }
    }

    @Override
    public OrderEventType getType() {
        return OrderEventType.ORDER_CREATED;
    }

    @Override
    public String toJson() {
        return "{\"orderId\":" + orderId +
                ",\"correlationId\":\"" + correlationId +
                "\",\"eventId\":\"" + eventId +
                "\",\"status\":\"" + status +
                "\",\"type\":\"" + getType().name() + "\"}";
    }
}