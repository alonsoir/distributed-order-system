package com.example.order.config;

import java.time.Duration;

/**
 * Configuración para el orquestador de sagas
 */
public class SagaConfig {

    // Circuit Breaker configuration
    public static final Duration CIRCUIT_BREAKER_WAIT_DURATION = Duration.ofSeconds(30);
    public static final int CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE = 10;
    public static final float CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD = 50.0f;
    public static final int CIRCUIT_BREAKER_PERMITTED_CALLS_IN_HALF_OPEN = 3;

    // Bulkhead configuration
    public static final int BULKHEAD_MAX_CONCURRENT_CALLS = 20;
    public static final Duration BULKHEAD_MAX_WAIT_DURATION = Duration.ofMillis(500);

    // Operation timeouts
    public static final Duration ORDER_SAGA_TIMEOUT = Duration.ofSeconds(15);

    // Metric names
    public static final String METRIC_SAGA_EXECUTION = "saga.execution.timer";
    public static final String METRIC_SAGA_STEP = "saga_step.timer";
    public static final String METRIC_ORDER_CREATION = "order.creation.timer";
    public static final String METRIC_EVENT_PUBLISH = "event.publish.timer";

    // Counter names
    public static final String COUNTER_SAGA_STEP_SUCCESS = "saga_step_success";
    public static final String COUNTER_SAGA_STEP_FAILED = "saga_step_failed";

    private SagaConfig() {
        // Utility class, no instances
    }
}