package com.example.order.service;

import reactor.core.publisher.Mono;

public interface InventoryService {
    Mono<Void> reserveStock(Long orderId, int quantity);
    Mono<Void> releaseStock(Long orderId, int quantity);
}