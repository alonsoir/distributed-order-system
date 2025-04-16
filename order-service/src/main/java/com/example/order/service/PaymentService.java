package com.example.order.service;

import reactor.core.publisher.Mono;

public interface PaymentService {
    Mono<Void> processPayment(Long orderId, double amount, String correlationId);
    Mono<Void> refund(Long orderId, double amount, String correlationId);
}