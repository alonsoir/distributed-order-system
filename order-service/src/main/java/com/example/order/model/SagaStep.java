package com.example.order.model;

import com.example.order.events.OrderEvent;
import lombok.Builder;
import lombok.Getter;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

@Getter
@Builder
public class SagaStep {
    private final String name;
    private final Supplier<Mono<?>> action;
    private final Function<String, OrderEvent> successEvent;
    private final Supplier<Mono<?>> compensation;
    private final Long orderId;
    private final String correlationId;
    private final String eventId;

    public SagaStep(String name, Supplier<Mono<?>> action, Function<String, OrderEvent> successEvent,
                    Supplier<Mono<?>> compensation, Long orderId, String correlationId, String eventId) {
        this.name = Objects.requireNonNull(name, "Step name cannot be null");
        this.action = Objects.requireNonNull(action, "Action cannot be null");
        this.successEvent = Objects.requireNonNull(successEvent, "Success event cannot be null");
        this.compensation = Objects.requireNonNull(compensation, "Compensation cannot be null");
        this.orderId = Objects.requireNonNull(orderId, "OrderId cannot be null");
        this.correlationId = Objects.requireNonNull(correlationId, "CorrelationId cannot be null");
        this.eventId = Objects.requireNonNull(eventId, "EventId cannot be null");
    }
}