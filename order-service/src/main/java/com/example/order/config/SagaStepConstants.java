package com.example.order.config;

/**
 * Constantes relacionadas con pasos de saga
 */
public final class SagaStepConstants {

    private SagaStepConstants() {
        // Utility class
    }

    // Step Names
    public static final String STEP_CREATE_ORDER = "createOrder";
    public static final String STEP_RESERVE_STOCK = "reserveStock";
    public static final String STEP_PROCESS_PAYMENT = "processPayment";
    public static final String STEP_FAILED_EVENT = "failedEvent";

    // Step Types
    public static final String STEP_TYPE_SAGA_STEP = "SAGA_STEP";
    public static final String STEP_TYPE_COMPENSATION = "COMPENSATION";
    public static final String STEP_TYPE_FAILED_EVENT = "FAILED_EVENT";
}
