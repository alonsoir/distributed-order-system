package com.example.order.events;

import com.example.order.domain.OrderStatus;
import lombok.Getter;

@Getter
public class OrderCompletedEvent implements OrderEvent{
    private final Long orderId;
    private final String correlationId;
    private final String eventId;
    private final String externalReference;

    public OrderCompletedEvent(Long orderId, String correlationId, String eventId,String externalReference) {
        this.orderId = orderId;
        this.correlationId = correlationId;
        this.eventId = eventId;
        this.externalReference = externalReference;
    }

    @Override
    public OrderStatus getType() {
        return OrderStatus.ORDER_COMPLETED;
    }

    @Override
    public String toJson() {
        return "{\"orderId\":" + orderId +
                ",\"correlationId\":\"" + correlationId +
                "\",\"eventId\":\"" + eventId +
                "\",\"externalReference\":\"" + externalReference +
                "\",\"type\":\"" + getType().name() + "\"}";
    }

    @Override
    public String getExternalReference() {
        return "";
    }
}
