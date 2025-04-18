package com.example.order.domain;

import java.util.Objects;

public record Order(Long id, String status, String correlationId) {
    public Order {
        Objects.requireNonNull(id, "Order ID must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        // correlationId puede ser null
    }
}