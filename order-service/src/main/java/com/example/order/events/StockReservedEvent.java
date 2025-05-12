package com.example.order.events;

import lombok.Getter;

@Getter
public class StockReservedEvent implements OrderEvent {
    private final Long orderId;
    private final String correlationId;
    private final String eventId;
    private String externalReference;
    private final int quantity;

    public StockReservedEvent(Long orderId, String correlationId, String eventId, String externalReference,int quantity) {
        validate(orderId, correlationId, eventId,externalReference);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        this.orderId = orderId;
        this.correlationId = correlationId;
        this.eventId = eventId;
        this.externalReference = externalReference;
        this.quantity = quantity;
    }

    private static void validate(Long orderId, String correlationId, String eventId,String externalReference) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("OrderId cannot be null and must be positive");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("CorrelationId cannot be null or empty");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("EventId cannot be null and must be positive");
        }
        if (externalReference == null || externalReference.isBlank()) {
            throw new IllegalArgumentException("externalReference cannot be null or empty");
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