package com.example.order.config;

/**
 * Categorías de Circuit Breakers para diferentes tipos de operaciones
 */
public enum CircuitBreakerCategory {
    // Operaciones con base de datos
    DATABASE_OPERATIONS("database-operations"),

    // Llamadas a servicios externos
    EXTERNAL_SERVICE("external-service"),

    // Procesamiento de comandos/sagas
    SAGA_PROCESSING("saga-processing"),

    // Publicación de eventos
    EVENT_PUBLISHING("event-publishing");

    private final String name;

    CircuitBreakerCategory(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}