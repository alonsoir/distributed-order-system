package com.example.order.model;

import com.example.order.events.OrderEvent;
import lombok.Builder;
import lombok.Getter;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;
import java.util.function.Function;

@Builder
@Getter
public class SagaStep {
    private final String name;
    private final String topic;
    private final Supplier<Mono<Void>> action;
    private final Supplier<Mono<Void>> compensation;
    private final Function<String, OrderEvent> successEvent;
    private final Long orderId;
    private final String correlationId;
    private final String eventId;
    private final String externalReference;

    public SagaStep(String name,
                    String topic,
                    Supplier<Mono<Void>> action,
                    Supplier<Mono<Void>> compensation,
                    Function<String, OrderEvent> successEvent,
                    Long orderId,
                    String correlationId,
                    String eventId,
                    String externalReference) {
        if (topic == null) {
            throw new IllegalArgumentException("Topic cannot be null");
        }
        this.name = name;
        this.topic = topic;
        this.action = action;
        this.compensation = compensation;
        this.successEvent = successEvent;
        this.orderId = orderId;
        this.correlationId = correlationId;
        this.eventId = eventId;
        this.externalReference = externalReference;

    }
}