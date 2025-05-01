package com.example.order.service;

import lombok.Getter;

@Getter
public class Result<T> {
    private final T event;
    private final boolean success;
    private final boolean outbox;
    private final boolean dlq;
    private final Throwable error;

    private Result(T event, boolean success, boolean outbox, boolean dlq, Throwable error) {
        this.event = event;
        this.success = success;
        this.outbox = outbox;
        this.dlq = dlq;
        this.error = error;
    }

    public static <T> Result<T> success(T event) {
        return new Result<>(event, true, false, false, null);
    }

    public static <T> Result<T> outbox(T event) {
        return new Result<>(event, false, true, false, null);
    }

    public static <T> Result<T> dlq(T event, Throwable error) {
        return new Result<>(event, false, false, true, error);
    }
}