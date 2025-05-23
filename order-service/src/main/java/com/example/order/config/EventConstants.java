package com.example.order.config;

/**
 * Constantes relacionadas con eventos y topics
 */
public final class EventConstants {

    private EventConstants() {
        // Utility class
    }

    // Event Types
    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_FAILED = "ORDER_FAILED";
    public static final String STOCK_RESERVED = "STOCK_RESERVED";
    public static final String PAYMENT_PROCESSED = "PAYMENT_PROCESSED";

    // Topic Names (si no están ya en EventTopics enum)
    public static final String TOPIC_ORDER_EVENTS = "order-events";
    public static final String TOPIC_INVENTORY_EVENTS = "inventory-events";
    public static final String TOPIC_PAYMENT_EVENTS = "payment-events";

    // Event Status
    public static final String EVENT_STATUS_STARTED = "STARTED";
    public static final String EVENT_STATUS_COMPLETED = "COMPLETED";
    public static final String EVENT_STATUS_FAILED = "FAILED";
    public static final String EVENT_STATUS_ATTEMPT = "ATTEMPT";
    public static final String EVENT_STATUS_SUCCESS = "SUCCESS";
    public static final String EVENT_STATUS_ERROR = "ERROR";
}