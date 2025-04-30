package com.example.order.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;

import java.io.IOException;

@Data
public class DefaultOrderEvent implements OrderEvent {
    private String eventId;
    private String correlationId;
    private Long orderId;
    private OrderEventType type;

    @Override
    public Long getOrderId() {
        return orderId;
    }

    @Override
    public String getCorrelationId() {
        return correlationId;
    }

    @Override
    public String getEventId() {
        return eventId;
    }

    @Override
    public OrderEventType getType() {
        return type;
    }

    @Override
    public String toJson() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            return mapper.writeValueAsString(this);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize event", e);
        }
    }

    public static DefaultOrderEvent fromJson(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            return mapper.readValue(json, DefaultOrderEvent.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize event", e);
        }
    }
}