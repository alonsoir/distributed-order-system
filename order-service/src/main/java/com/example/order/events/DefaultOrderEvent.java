package com.example.order.events;

import com.example.order.domain.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Implementación por defecto de un evento de orden.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaultOrderEvent implements OrderEvent {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private String eventId;
    private String correlationId;
    private String externalReference;
    private Long orderId;
    private OrderStatus type;
    private String payload;

    @Override
    public String getEventId() {
        return eventId;
    }

    @Override
    public String getCorrelationId() {
        return correlationId;
    }

    @Override
    public Long getOrderId() {
        return orderId;
    }

    @Override
    public OrderStatus getType() {
        return type;
    }

    @Override
    public String toJson() {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Error converting event to JSON", e);
        }
    }

    /**
     * Crea un evento a partir de su representación JSON.
     *
     * @param json Representación JSON del evento
     * @return El evento reconstruido
     */
    public static DefaultOrderEvent fromJson(String json) {
        try {
            return objectMapper.readValue(json, DefaultOrderEvent.class);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing JSON to event", e);
        }
    }
}