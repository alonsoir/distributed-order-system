package com.example.order.model;

import com.example.order.events.OrderEvent;
import lombok.Getter;
import reactor.core.publisher.Mono;

import java.util.function.Function;
import java.util.function.Supplier;

@Getter
public class SagaStep {
    private final String name;
    private final String topic;
    private final SagaStepType stepType;
    private final Supplier<Mono<Void>> action;
    private final Supplier<Mono<Void>> compensation;
    private final Function<String, OrderEvent> successEvent;
    private final Long orderId;
    private final String correlationId;
    private final String eventId;
    private final String externalReference;

    private SagaStep(Builder builder) {
        this.name = builder.name;
        this.topic = builder.topic;
        this.stepType = builder.stepType;
        this.action = builder.action;
        this.compensation = builder.compensation;
        this.successEvent = builder.successEvent;
        this.orderId = builder.orderId;
        this.correlationId = builder.correlationId;
        this.eventId = builder.eventId;
        this.externalReference = builder.externalReference;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String topic;
        private SagaStepType stepType;
        private Supplier<Mono<Void>> action;
        private Supplier<Mono<Void>> compensation;
        private Function<String, OrderEvent> successEvent;
        private Long orderId;
        private String correlationId;
        private String eventId;
        private String externalReference;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder stepType(SagaStepType stepType) {
            this.stepType = stepType;
            return this;
        }

        public Builder action(Supplier<Mono<Void>> action) {
            this.action = action;
            return this;
        }

        public Builder compensation(Supplier<Mono<Void>> compensation) {
            this.compensation = compensation;
            return this;
        }

        public Builder successEvent(Function<String, OrderEvent> successEvent) {
            this.successEvent = successEvent;
            return this;
        }

        public Builder orderId(Long orderId) {
            this.orderId = orderId;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder externalReference(String externalReference) {
            this.externalReference = externalReference;
            return this;
        }

        public SagaStep build() {
            // Validaciones y valores por defecto
            if (topic == null) {
                throw new IllegalArgumentException("Topic cannot be null");
            }

            // Proporcionar un valor por defecto para stepType si no se especificó
            if (stepType == null) {
                stepType = SagaStepType.GENERIC_STEP;
            }

            // No validamos compensation aquí para permitir valores nulos
            // La validación se realizará en las clases consumidoras como CompensationManager

            return new SagaStep(this);
        }
    }

    // Método para evolucionar el paso a un nuevo estado
    public SagaStep evolve(SagaStepType newStepType) {
        return SagaStep.builder()
                .name(this.name)
                .topic(this.topic)
                .stepType(newStepType)
                .action(this.action)
                .compensation(this.compensation)
                .successEvent(this.successEvent)
                .orderId(this.orderId)
                .correlationId(this.correlationId)
                .eventId(this.eventId)
                .externalReference(this.externalReference)
                .build();
    }

    // Método para cambiar la acción
    public SagaStep withAction(Supplier<Mono<Void>> newAction) {
        return SagaStep.builder()
                .name(this.name)
                .topic(this.topic)
                .stepType(this.stepType)
                .action(newAction)
                .compensation(this.compensation)
                .successEvent(this.successEvent)
                .orderId(this.orderId)
                .correlationId(this.correlationId)
                .eventId(this.eventId)
                .externalReference(this.externalReference)
                .build();
    }

    // Método para cambiar la compensación
    public SagaStep withCompensation(Supplier<Mono<Void>> newCompensation) {
        return SagaStep.builder()
                .name(this.name)
                .topic(this.topic)
                .stepType(this.stepType)
                .action(this.action)
                .compensation(newCompensation)
                .successEvent(this.successEvent)
                .orderId(this.orderId)
                .correlationId(this.correlationId)
                .eventId(this.eventId)
                .externalReference(this.externalReference)
                .build();
    }
}