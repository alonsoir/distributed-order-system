package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.model.SagaStep;
import reactor.core.publisher.Mono;

public interface SagaOrchestrator {
    Mono<Order> executeOrderSaga(int quantity, double amount);
    Mono<Order> createOrder(Long orderId, String correlationId, String eventId);
    Mono<OrderEvent> executeStep(SagaStep step);
    Mono<Void> publishFailedEvent(OrderFailedEvent event);
    Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic);
    Mono<Void> createFailedEvent(String reason, String externalReference);
}
