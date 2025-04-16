package com.example.order.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class InventoryServiceImpl implements InventoryService {
    private final Map<Long, Integer> inventory = new ConcurrentHashMap<>();
    private final Map<Long, Integer> reserved = new ConcurrentHashMap<>();

    public InventoryServiceImpl() {
        inventory.put(1L, 100); // Simulamos 100 unidades del producto 1
    }

    @Override
    public Mono<Void> reserveStock(Long orderId, int quantity) {
        log.info("Reserving {} units for order {}", quantity, orderId);
        return Mono.fromCallable(() -> {
            synchronized (inventory) {
                Integer available = inventory.getOrDefault(1L, 0);
                if (available < quantity) {
                    throw new RuntimeException("Insufficient stock for order " + orderId);
                }
                inventory.put(1L, available - quantity);
                reserved.put(orderId, quantity);
                return null;
            }
        }).then();
    }

    @Override
    public Mono<Void> releaseStock(Long orderId, int quantity) {
        log.info("Releasing {} units for order {}", quantity, orderId);
        return Mono.fromCallable(() -> {
            synchronized (inventory) {
                Integer reservedQty = reserved.getOrDefault(orderId, 0);
                inventory.compute(1L, (k, v) -> v == null ? reservedQty : v + reservedQty);
                reserved.remove(orderId);
                return null;
            }
        }).then();
    }
}