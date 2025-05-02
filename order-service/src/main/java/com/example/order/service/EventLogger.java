package com.example.order.service;

import com.example.order.events.OrderEvent;
import reactor.core.publisher.Mono;

/**
 * Interface for event logging functionality
 */
public interface EventLogger {

    /**
     * Logs an event to a persistent storage for recovery
     *
     * @param event The event to log
     * @param topic The intended topic for the event
     * @param error The error that caused the logging need
     * @return A Mono that completes when the event is successfully logged
     */
    Mono<Void> logEvent(OrderEvent event, String topic, Throwable error);
}