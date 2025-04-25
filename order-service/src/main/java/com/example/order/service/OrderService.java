package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.events.OrderEvent;
import com.example.order.model.SagaStep;
import reactor.core.publisher.Mono;

public interface OrderService {
    Mono<Order> processOrder(Long orderId, int quantity, double amount);
    Mono<Order> createOrder(Long orderId, String correlationId);
    Mono<Void> publishEvent(OrderEvent event);
    Mono<OrderEvent> executeStep(SagaStep step);
}