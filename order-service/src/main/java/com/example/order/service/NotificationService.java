package com.example.order.service;

import reactor.core.publisher.Mono;

public interface NotificationService {
    Mono<Void> notifyUser(Long orderId);
}