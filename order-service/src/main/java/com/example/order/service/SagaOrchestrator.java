package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import reactor.core.publisher.Mono;

public interface SagaOrchestrator {
    public Mono<Order> executeOrderSaga(int quantity, double amount );
    public Mono<Void> publishFailedEvent(OrderFailedEvent event);
    public Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic);
    public Mono<Void> createFailedEvent(String reason, String externalReference);
}
