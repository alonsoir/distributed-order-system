package com.example.order.service;

public class Result<T> {
    private final T event;
    private final Status status;
    private final Throwable error;

    private Result(T event, Status status, Throwable error) {
        this.event = event;
        this.status = status;
        this.error = error;
    }

    public static <T> Result<T> success(T event) {
        return new Result<>(event, Status.SUCCESS, null);
    }

    public static <T> Result<T> redisFailure(T event, Throwable error) {
        return new Result<>(event, Status.REDIS_FAILURE, error);
    }

    public static <T> Result<T> outbox(T event) {
        return new Result<>(event, Status.OUTBOX, null);
    }

    public static <T> Result<T> dlq(T event, Throwable error) {
        return new Result<>(event, Status.DLQ, error);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public T getEvent() {
        return event;
    }

    public Status getStatus() {
        return status;
    }

    public Throwable getError() {
        return error;
    }

    public enum Status {
        SUCCESS, REDIS_FAILURE, OUTBOX, DLQ
    }
}