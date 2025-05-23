package com.example.order.config;

/**
 * Constantes relacionadas con Circuit Breakers
 */
public final class CircuitBreakerConstants {

    private CircuitBreakerConstants() {
        // Utility class
    }

    // Circuit Breaker Names
    public static final String ORDER_PROCESSING = "orderProcessing";
    public static final String DATABASE_OPERATIONS = "databaseOperations";
    public static final String EVENT_PUBLISHING = "eventPublishing";
    public static final String INVENTORY_SERVICE = "inventoryService";

    // Circuit Breaker Error Reasons
    public static final String CIRCUIT_BREAKER_OPEN = "circuit_breaker_open";
    public static final String CIRCUIT_BREAKER_ERROR = "circuit_breaker_error";
    public static final String GLOBAL_TIMEOUT = "global_timeout";
}