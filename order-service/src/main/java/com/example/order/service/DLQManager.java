package com.example.order.service;

import com.example.order.events.OrderEvent;
import reactor.core.publisher.Mono;

public interface DLQManager {
    /**
     * Pushes an event to the Dead Letter Queue
     *
     * @param event Event to be pushed to DLQ
     * @param error Error that caused the event to be pushed to DLQ
     * @param step Processing step where the error occurred
     * @param topic Intended topic for the event
     * @return Mono that completes when the event is pushed to DLQ
     */
    Mono<EventPublishOutcome<OrderEvent>> pushToDLQ(OrderEvent event, Throwable error, String step, String topic);
}
