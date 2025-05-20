package com.example.order.repository;

import com.example.order.domain.DeliveryMode;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.events.OrderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.util.ConditionalOnBootstrapDisabled;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * @deprecated Esta implementación ha sido reemplazada por {@link CompositeEventRepository}
 * y sus componentes. Será eliminada en futuras versiones.
 */
@Deprecated(since = "2.0.0", forRemoval = true)
@Repository
@ConditionalOnBootstrapDisabled
public class R2dbcEventRepository implements EventRepository {
    private static final Logger log = LoggerFactory.getLogger(R2dbcEventRepository.class);

    // Configuración de timeouts y reintentos
    private static final Duration DB_OPERATION_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_DB_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(100);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);
    private static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 30;

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;

    public R2dbcEventRepository(DatabaseClient databaseClient, TransactionalOperator transactionalOperator) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = transactionalOperator;
    }

    @Override
    public Mono<Boolean> isEventProcessed(String eventId) {
        if (eventId == null) {
            return Mono.error(new IllegalArgumentException("eventId cannot be null"));
        }

        return databaseClient.sql("SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId")
                .bind("eventId", eventId)
                .map((row, metadata) -> {
                    try {
                        return row.get(0, Integer.class);
                    } catch (Exception e) {
                        log.warn("Error al mapear respuesta de BD: {}", e.getMessage());
                        return 0;
                    }
                })
                .one()
                .map(count -> count > 0)
                .defaultIfEmpty(false)
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("isEventProcessed"))
                .onErrorResume(e -> {
                    log.error("Error checking if event is processed: {}", e.getMessage(), e);
                    return Mono.just(false);
                });
    }

    @Override
    public Mono<Boolean> isEventProcessed(String eventId, DeliveryMode deliveryMode) {
        if (eventId == null) {
            return Mono.error(new IllegalArgumentException("eventId cannot be null"));
        }
        if (deliveryMode == null) {
            return Mono.error(new IllegalArgumentException("deliveryMode cannot be null"));
        }

        return databaseClient.sql("SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId AND delivery_mode = :deliveryMode")
                .bind("eventId", eventId)
                .bind("deliveryMode", deliveryMode.name())
                .map((row, metadata) -> {
                    try {
                        return row.get(0, Integer.class);
                    } catch (Exception e) {
                        log.warn("Error al mapear respuesta de BD: {}", e.getMessage());
                        return 0;
                    }
                })
                .one()
                .map(count -> count > 0)
                .defaultIfEmpty(false)
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("isEventProcessed"))
                .onErrorResume(e -> {
                    log.error("Error checking if event is processed with delivery mode {}: {}", deliveryMode, e.getMessage(), e);
                    return Mono.just(false);
                });
    }

    @Override
    public Mono<Void> markEventAsProcessed(String eventId) {
        if (eventId == null) {
            return Mono.error(new IllegalArgumentException("eventId cannot be null"));
        }

        return databaseClient.sql("INSERT INTO processed_events (event_id, processed_at) VALUES (:eventId, CURRENT_TIMESTAMP)")
                .bind("eventId", eventId)
                .then()
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("markEventAsProcessed"))
                .onErrorResume(e -> {
                    log.error("Error marking event as processed: {}", e.getMessage(), e);
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Void> markEventAsProcessed(String eventId, DeliveryMode deliveryMode) {
        if (eventId == null) {
            return Mono.error(new IllegalArgumentException("eventId cannot be null"));
        }
        if (deliveryMode == null) {
            return Mono.error(new IllegalArgumentException("deliveryMode cannot be null"));
        }

        return databaseClient.sql("INSERT INTO processed_events (event_id, processed_at, delivery_mode) VALUES (:eventId, CURRENT_TIMESTAMP, :deliveryMode)")
                .bind("eventId", eventId)
                .bind("deliveryMode", deliveryMode.name())
                .then()
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("markEventAsProcessed"))
                .onErrorResume(e -> {
                    log.error("Error marking event as processed with delivery mode {}: {}", deliveryMode, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Boolean> checkAndMarkEventAsProcessed(String eventId, DeliveryMode deliveryMode) {
        return null;
    }

    @Override
    public Mono<Order> findOrderById(Long orderId) {
        if (orderId == null) {
            return Mono.error(new IllegalArgumentException("orderId cannot be null"));
        }

        return databaseClient.sql("SELECT id, status, correlation_id FROM orders WHERE id = :id")
                .bind("id", orderId)
                .map((row, metadata) -> new Order(
                        row.get("id", Long.class),
                        row.get("status", String.class),
                        row.get("correlation_id", String.class)
                ))
                .one()
                .switchIfEmpty(Mono.error(new IllegalStateException("Order not found: " + orderId)))
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("findOrderById"))
                .onErrorResume(e -> {
                    log.error("Error finding order by id: {}", e.getMessage(), e);
                    if (e instanceof IllegalStateException && e.getMessage().contains("Order not found")) {
                        // Propagate our custom error
                        return Mono.error(e);
                    }
                    // For other errors (like DB errors), create a generic message
                    return Mono.error(new IllegalStateException("Order not found: " + orderId, e));
                });
    }
    @Override
    public Mono<Void> saveOrderData(Long orderId, String correlationId, String eventId, OrderEvent event) {
        return saveOrderData(orderId, correlationId, eventId, event, DeliveryMode.AT_LEAST_ONCE);
    }

    @Override
    public Mono<Void> saveOrderData(Long orderId, String correlationId, String eventId, OrderEvent event, DeliveryMode deliveryMode) {
        if (orderId == null || correlationId == null || eventId == null || event == null) {
            return Mono.error(new IllegalArgumentException("Required parameters cannot be null"));
        }

        // Crear una variable final con el valor correcto
        final DeliveryMode effectiveDeliveryMode = deliveryMode != null ? deliveryMode : DeliveryMode.AT_LEAST_ONCE;

        // Para AT_MOST_ONCE, primero verificamos si el evento ya ha sido procesado
        if (effectiveDeliveryMode == DeliveryMode.AT_MOST_ONCE) {
            return isEventProcessed(eventId, effectiveDeliveryMode)
                    .flatMap(processed -> {
                        if (processed) {
                            log.info("Event {} already processed (AT_MOST_ONCE), skipping", eventId);
                            return Mono.empty();
                        }
                        return saveOrderDataInternal(orderId, correlationId, eventId, event, effectiveDeliveryMode);
                    });
        }

        // Para AT_LEAST_ONCE, procedemos directamente
        return saveOrderDataInternal(orderId, correlationId, eventId, event, effectiveDeliveryMode);
    }

    private Mono<Void> saveOrderDataInternal(Long orderId, String correlationId, String eventId, OrderEvent event, DeliveryMode deliveryMode) {
        return transactionalOperator.transactional(
                databaseClient.sql(
                                "INSERT INTO orders (id, status, correlation_id, created_at, updated_at, delivery_mode) " +
                                        "VALUES (:id, :status, :correlationId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, :deliveryMode)")
                        .bind("id", orderId)
                        .bind("status", "pending")
                        .bind("correlationId", correlationId)
                        .bind("deliveryMode", deliveryMode.name())
                        .then()
                        .doOnSuccess(v -> log.info("Inserted order {} into orders table", orderId))
                        .doOnError(e -> log.error("Failed to insert order {} into table: {}", orderId, e.getMessage(), e))
                        .then(databaseClient.sql(
                                        "INSERT INTO outbox (event_type, correlation_id, event_id, payload, created_at, status, topic, delivery_mode) " +
                                                "VALUES (:event_type, :correlationId, :eventId, :payload, CURRENT_TIMESTAMP, 'PENDING', 'orders', :deliveryMode)")
                                .bind("event_type", event.getType().name())
                                .bind("correlationId", correlationId)
                                .bind("eventId", eventId)
                                .bind("payload", event.toJson())
                                .bind("deliveryMode", deliveryMode.name())
                                .then())
                        .doOnSuccess(v -> log.info("Inserted outbox event for order {}", orderId))
                        .doOnError(e -> log.error("Failed to insert outbox event for order {}: {}", orderId, e.getMessage(), e))
                        .then(markEventAsProcessed(eventId, deliveryMode))
                        .doOnSuccess(v -> log.info("Marked event as processed for order {}", orderId))
                        .doOnError(e -> log.error("Failed to mark event as processed for order {}: {}", orderId, e.getMessage(), e))
        );
    }

    @Override
    public Mono<Order> updateOrderStatus(Long orderId, OrderStatus status, String correlationId) {
        if (orderId == null || status == null || correlationId == null) {
            return Mono.error(new IllegalArgumentException("orderId, status, and correlationId cannot be null"));
        }

        log.info("Updating order {} status to {}", orderId, status);

        return databaseClient.sql("UPDATE orders SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .bind("status", status.getValue())
                .bind("id", orderId)
                .then()
                .doOnSuccess(v -> log.info("Updated order {} status to {}", orderId, status))
                .doOnError(e -> log.error("Failed to update order {} status: {}", orderId, e.getMessage(), e))
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("updateOrderStatus"))
                .then(insertStatusAuditLog(orderId, status, correlationId))
                .then(Mono.just(new Order(orderId, status, correlationId)));
    }

    @Override
    public Mono<Void> insertStatusAuditLog(Long orderId, OrderStatus status, String correlationId) {
        return databaseClient.sql(
                        "INSERT INTO order_status_history (order_id, status, correlation_id, timestamp) " +
                                "VALUES (:orderId, :status, :correlationId, CURRENT_TIMESTAMP)")
                .bind("orderId", orderId)
                .bind("status", status.getValue())
                .bind("correlationId", correlationId)
                .then()
                .onErrorResume(e -> {
                    log.warn("Failed to insert status audit log, continuing: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Void> insertCompensationLog(String stepName, Long orderId, String correlationId, String eventId, OrderStatus status) {
        return databaseClient.sql(
                        "INSERT INTO compensation_log (step_name, order_id, correlation_id, event_id, status, timestamp) " +
                                "VALUES (:stepName, :orderId, :correlationId, :eventId, :status, CURRENT_TIMESTAMP)")
                .bind("stepName", stepName)
                .bind("orderId", orderId)
                .bind("correlationId", correlationId)
                .bind("eventId", eventId)
                .bind("status", status.getValue())
                .then()
                .onErrorResume(e -> {
                    log.error("Failed to log compensation status: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Void> recordStepFailure(String stepName, Long orderId, String correlationId, String eventId,
                                        String errorMessage, String errorType, String errorCategory) {
        // Manejo de errorCategory nulo
        String category = errorCategory != null ? errorCategory : "UNKNOWN";

        return databaseClient.sql(
                        "INSERT INTO saga_step_failures (step_name, order_id, correlation_id, event_id, " +
                                "error_message, error_type, error_category, timestamp) " +
                                "VALUES (:stepName, :orderId, :correlationId, :eventId, :errorMessage, :errorType, :errorCategory, CURRENT_TIMESTAMP)")
                .bind("stepName", stepName)
                .bind("orderId", orderId)
                .bind("correlationId", correlationId)
                .bind("eventId", eventId)
                .bind("errorMessage", errorMessage != null ? errorMessage : "No error message provided")
                .bind("errorType", errorType)
                .bind("errorCategory", category)
                .then()
                .onErrorResume(e -> {
                    log.error("Failed to record step failure: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Void> recordSagaFailure(Long orderId, String correlationId, String errorMessage, String errorType, String errorCategory) {
        return recordSagaFailure(orderId, correlationId, errorMessage, errorType, DeliveryMode.AT_LEAST_ONCE);
    }

    @Override
    public Mono<Void> recordSagaFailure(Long orderId, String correlationId, String errorMessage, String errorType, DeliveryMode deliveryMode) {
        // Manejo de valores nulos
        String errorMsg = errorMessage != null ? errorMessage : "No error message provided";
        String delivery = deliveryMode != null ? deliveryMode.name() : DeliveryMode.AT_LEAST_ONCE.name();

        return databaseClient.sql(
                        "INSERT INTO saga_failures (order_id, correlation_id, error_message, error_type, " +
                                "delivery_mode, timestamp) VALUES (:orderId, :correlationId, :errorMessage, " +
                                ":errorType, :deliveryMode, CURRENT_TIMESTAMP)")
                .bind("orderId", orderId)
                .bind("correlationId", correlationId)
                .bind("errorMessage", errorMsg)
                .bind("errorType", errorType)
                .bind("deliveryMode", delivery)
                .then()
                .onErrorResume(e -> {
                    log.error("Failed to record saga failure: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Void> saveEventHistory(String eventId, String correlationId, Long orderId, String eventType, String operation, String outcome) {
        return saveEventHistory(eventId, correlationId, orderId, eventType, operation, outcome, DeliveryMode.AT_LEAST_ONCE);
    }

    @Override
    public Mono<Void> saveEventHistory(String eventId, String correlationId, Long orderId, String eventType, String operation, String outcome, DeliveryMode deliveryMode) {
        String delivery = deliveryMode != null ? deliveryMode.name() : DeliveryMode.AT_LEAST_ONCE.name();

        return databaseClient.sql(
                        "INSERT INTO event_history (event_id, correlation_id, order_id, event_type, " +
                                "operation, outcome, timestamp, delivery_mode) VALUES " +
                                "(:eventId, :correlationId, :orderId, :eventType, :operation, :outcome, CURRENT_TIMESTAMP, :deliveryMode)")
                .bind("eventId", eventId)
                .bind("correlationId", correlationId)
                .bind("orderId", orderId)
                .bind("eventType", eventType)
                .bind("operation", operation)
                .bind("outcome", outcome)
                .bind("deliveryMode", delivery)
                .then()
                .onErrorResume(e -> {
                    log.warn("Failed to save event history: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Boolean> acquireTransactionLock(String resourceId, String correlationId, int timeoutSeconds) {
        if (resourceId == null || correlationId == null) {
            return Mono.error(new IllegalArgumentException("resourceId and correlationId cannot be null"));
        }

        // Usar un timeout por defecto si el valor es negativo o cero
        int effectiveTimeout = timeoutSeconds <= 0 ? DEFAULT_LOCK_TIMEOUT_SECONDS : timeoutSeconds;

        // Calcular la hora de expiración del bloqueo
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(effectiveTimeout);

        return databaseClient.sql(
                        "INSERT INTO transaction_locks (resource_id, correlation_id, locked_at, expires_at) " +
                                "VALUES (:resourceId, :correlationId, CURRENT_TIMESTAMP, :expiresAt)")
                .bind("resourceId", resourceId)
                .bind("correlationId", correlationId)
                .bind("expiresAt", expiresAt)
                .then()
                .thenReturn(true)
                .onErrorResume(e -> {
                    log.warn("Failed to acquire lock for resource {}: {}", resourceId, e.getMessage());
                    return Mono.just(false);
                });
    }

    @Override
    public Mono<Void> releaseTransactionLock(String resourceId, String correlationId) {
        if (resourceId == null || correlationId == null) {
            return Mono.error(new IllegalArgumentException("resourceId and correlationId cannot be null"));
        }

        return databaseClient.sql(
                        "UPDATE transaction_locks SET released = TRUE WHERE resource_id = :resourceId AND correlation_id = :correlationId")
                .bind("resourceId", resourceId)
                .bind("correlationId", correlationId)
                .then()
                .onErrorResume(e -> {
                    log.warn("Failed to release lock for resource {}: {}", resourceId, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Crea una especificación de reintento para operaciones de base de datos
     */
    private Retry createRetrySpec(String operation) {
        return Retry.backoff(MAX_DB_RETRIES, INITIAL_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .doBeforeRetry(retrySignal -> {
                    log.warn("Retrying database operation {} after error, attempt: {}/{}",
                            operation, retrySignal.totalRetries() + 1, MAX_DB_RETRIES);
                });
    }
}