package com.example.order.domain;

import java.util.Objects;

public record Order(Long id, String status, String correlationId) {
    public Order {
        // El id puede ser null porque se generará en SagaOrchestratorAtLeastOnceImpl si es necesario
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
    }
}