package com.example.order.config;

import java.time.Duration;

/**
 * Constantes relacionadas con métricas y contadores
 * TODO : Revisar y ajustar los valores según las necesidades del sistema
 * Incluso estos valores pueden ser ajustados en tiempo de ejecución puesto que la ejecucion de
 * la aplicacion se realiza en un contenedor Docker y su comportamiento físico puede variar.
 * Sería una gran mejora poder ajustar estos valores en tiempo de ejecución según las necesidades
 * del sistema, las métricas.
 */
public final class MetricsConstants {

    private MetricsConstants() {
        // Utility class
    }

    // Circuit Breaker configuration
    public static final Duration CIRCUIT_BREAKER_WAIT_DURATION = Duration.ofSeconds(30);
    public static final int CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE = 10;
    public static final float CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD = 50.0f;
    public static final int CIRCUIT_BREAKER_PERMITTED_CALLS_IN_HALF_OPEN = 3;
    public static final int DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD = 5;

    // Bulkhead configuration
    public static final int BULKHEAD_MAX_CONCURRENT_CALLS = 20;
    public static final Duration BULKHEAD_MAX_WAIT_DURATION = Duration.ofMillis(500);

    // Operation timeouts
    public static final Duration ORDER_SAGA_TIMEOUT = Duration.ofSeconds(15);

    // Timer Names (consolidados)
    public static final String SAGA_EXECUTION_TIMER = "saga.execution.timer";
    public static final String SAGA_STEP_TIMER = "saga_step.timer";
    public static final String ORDER_CREATION_TIMER = "order.creation.timer";
    public static final String EVENT_PUBLISH_TIMER = "event.publish.timer";
    public static final String ORDER_PROCESSING_DURATION = "order_processing_duration";

    // Counter Names
    public static final String COUNTER_SAGA_STEP_SUCCESS = "saga_step_success";
    public static final String COUNTER_SAGA_STEP_FAILED = "saga_step_failed";
    public static final String ORDERS_SUCCESS = "orders_success";
    public static final String ORDERS_FAILED = "orders_failed";
    public static final String CIRCUIT_BREAKER_OPENS = "circuit_breaker_opens";
    public static final String SAGA_EXECUTIONS = "saga_executions";
    public static final String SAGA_FAILURES = "saga_failures";

    // Metric names
    public static final String METRIC_SAGA_EXECUTION = "saga.execution.timer";
    public static final String METRIC_SAGA_STEP = "saga_step.timer";
    public static final String METRIC_ORDER_CREATION = "order.creation.timer";
    public static final String METRIC_EVENT_PUBLISH = "event.publish.timer";

    // Timer Names
    public static final String SAGA_EXECUTION_DURATION = "saga_execution_duration";

    // Gauge Names
    public static final String ACTIVE_ORDERS = "active_orders";
    public static final String PENDING_COMPENSATION = "pending_compensation";

    // DB
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(100);
    public static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofSeconds(5);


}