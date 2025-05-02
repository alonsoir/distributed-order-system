package com.example.order.service;

import lombok.Getter;

@Getter
public class EventPublishOutcome<T> {
    private final T event;
    private final boolean success;
    private final boolean outbox;
    private final boolean dlq;
    private final boolean dlqFailure;
    private final Throwable error;

    private EventPublishOutcome(T event, boolean success, boolean outbox, boolean dlq, boolean dlqFailure, Throwable error) {
        this.event = event;
        this.success = success;
        this.outbox = outbox;
        this.dlq = dlq;
        this.dlqFailure = dlqFailure;
        this.error = error;
    }

    public static <T> EventPublishOutcome<T> success(T event) {
        return new EventPublishOutcome<>(event, true, false, false, false, null);
    }

    public static <T> EventPublishOutcome<T> outbox(T event) {
        return new EventPublishOutcome<>(event, false, true, false, false, null);
    }

    public static <T> EventPublishOutcome<T> dlq(T event, Throwable error) {
        return new EventPublishOutcome<>(event, false, false, true, false, error);
    }

    public static <T> EventPublishOutcome<T> dlqFailure(T event, Throwable error) {
        return new EventPublishOutcome<>(event, false, false, false, true, error);
    }
}