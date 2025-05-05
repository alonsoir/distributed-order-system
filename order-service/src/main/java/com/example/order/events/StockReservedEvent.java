package com.example.order.events;

import lombok.Getter;

@Getter
public class StockReservedEvent implements OrderEvent {
    private final Long orderId;
    private final String correlationId;
    private final String eventId;
    private final int quantity;

    public StockReservedEvent(Long orderId, String correlationId, String eventId, int quantity) {
        validate(orderId, correlationId, eventId);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        this.orderId = orderId;
        this.correlationId = correlationId;
        this.eventId = eventId;
        this.quantity = quantity;
    }

    private static void validate(Long orderId, String correlationId, String eventId) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("OrderId cannot be null and must be positive");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("CorrelationId cannot be null or empty");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("EventId cannot be null and must be positive");
        }
    }

    @Override
    public OrderEventType getType() {
        return OrderEventType.STOCK_RESERVED;
    }

    @Override
    public String toJson() {
        return "{\"orderId\":" + orderId +
                ",\"correlationId\":\"" + correlationId +
                "\",\"eventId\":\"" + eventId +
                "\",\"quantity\":" + quantity +
                "\",\"type\":\"" + getType().name() + "\"}";
    }
}