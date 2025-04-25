package com.example.order.events;

public interface OrderEvent {
    Long getOrderId();
    String getCorrelationId();
    String getEventId();
    OrderEventType getType();
    String toJson();
}