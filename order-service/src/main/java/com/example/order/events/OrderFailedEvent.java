package com.example.order.events;

import lombok.Getter;

@Getter
public class OrderFailedEvent implements OrderEvent {
    private final Long orderId;
    private final String correlationId;
    private final String eventId;
    private final String step;
    private final String reason;

    public OrderFailedEvent(Long orderId, String correlationId, String eventId, String step, String reason) {
        validate(orderId, correlationId, eventId);
        if (step == null || step.isBlank()) {
            throw new IllegalArgumentException("Step cannot be null or empty");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reason cannot be null or empty");
        }
        this.orderId = orderId;
        this.correlationId = correlationId;
        this.eventId = eventId;
        this.step = step;
        this.reason = reason;
    }

    private static void validate(Long orderId, String correlationId, String eventId) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("OrderId must be positive");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("CorrelationId cannot be null or empty");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("EventId cannot be null or empty");
        }
    }

    @Override
    public OrderEventType getType() {
        return OrderEventType.ORDER_FAILED;
    }

    @Override
    public String toJson() {
        return "{\"orderId\":" + orderId +
                ",\"correlationId\":\"" + correlationId +
                "\",\"eventId\":\"" + eventId +
                "\",\"step\":\"" + step +
                "\",\"reason\":\"" + reason +
                "\",\"type\":\"" + getType().name() + "\"}";
    }
}