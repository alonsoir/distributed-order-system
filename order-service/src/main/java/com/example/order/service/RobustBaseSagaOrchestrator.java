package com.example.order.service;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.config.SagaConfig;
import com.example.order.domain.Order;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.events.StockReservedEvent;
import com.example.order.model.SagaStep;
import com.example.order.resilience.ResilienceManager;
import com.example.order.utils.ReactiveUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;  // Añadir este import
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;

/**
 * Implementación base reforzada para orquestadores de sagas con funcionalidad común
 */
public abstract class RobustBaseSagaOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(RobustBaseSagaOrchestrator.class);

    // Configuración de timeouts
    protected static final Duration DB_OPERATION_TIMEOUT = Duration.ofSeconds(10);
    protected static final Duration EVENT_PUBLISH_TIMEOUT = Duration.ofSeconds(15);
    protected static final Duration SAGA_STEP_TIMEOUT = Duration.ofSeconds(30);
    protected static final Duration GLOBAL_SAGA_TIMEOUT = Duration.ofMinutes(5);

    // Configuración de reintentos
    protected static final int MAX_DB_RETRIES = 3;
    protected static final int MAX_EVENT_RETRIES = 2;
    protected static final Duration INITIAL_BACKOFF = Duration.ofMillis(100);
    protected static final Duration MAX_BACKOFF = Duration.ofSeconds(2);

    protected final DatabaseClient databaseClient;
    protected final TransactionalOperator transactionalOperator;
    protected final MeterRegistry meterRegistry;
    protected final IdGenerator idGenerator;
    protected final ResilienceManager resilienceManager;
    protected final EventPublisher eventPublisher;

    protected RobustBaseSagaOrchestrator(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            EventPublisher eventPublisher) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = transactionalOperator;
        this.meterRegistry = meterRegistry;
        this.idGenerator = idGenerator;
        this.resilienceManager = resilienceManager;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Maneja un error en un paso de saga con clasificación de errores y estrategia de recuperación
     */
    protected Mono<OrderEvent> handleStepError(SagaStep step, Throwable e, CompensationManager compensationManager) {
        if (step == null) {
            return Mono.error(new IllegalArgumentException("SagaStep cannot be null"));
        }

        if (compensationManager == null) {
            log.error("CompensationManager is null, cannot execute compensation for step {}",
                    step.getName());
            return Mono.error(e);
        }

        ErrorType errorType = classifyError(e);

        log.error("Step {} failed with error type {}: {}",
                step.getName(), errorType, e.getMessage(), e);

        OrderFailedEvent failedEvent = new OrderFailedEvent(
                step.getOrderId(),
                step.getCorrelationId(),
                step.getEventId(),
                step.getName(),
                String.format("%s: %s [Type: %s]", e.getClass().getSimpleName(), e.getMessage(), errorType).toString(),
                step.getExternalReference());

        // Actualizar métrica de errores con tipo
        meterRegistry.counter("saga.step.error",
                "step", step.getName(),
                "error_type", errorType.name(),
                "exception", e.getClass().getSimpleName()).increment();

        // Para errores transitorios, podemos reintentar antes de compensar
        if (errorType == ErrorType.TRANSIENT) {
            // La lógica de reintento debería manejarse más arriba en el flujo
            // Aquí solo manejamos el error después de reintentos
            log.warn("Transient error in step {} after retries exhausted", step.getName());
        }

        return publishFailedEvent(failedEvent)
                .then(executeRobustCompensation(step, compensationManager))
                .then(recordStepFailure(step, e, errorType))
                .then(Mono.error(e));
    }

    /**
     * Crea un paso de reserva de stock con validación reforzada
     */
    protected SagaStep createReserveStockStep(
            InventoryService inventoryService,
            Long orderId,
            int quantity,
            String correlationId,
            String eventId,
            String externalReference) {

        if (inventoryService == null) {
            throw new IllegalArgumentException("InventoryService cannot be null");
        }

        if (orderId == null || correlationId == null || eventId == null) {
            throw new IllegalArgumentException("orderId, correlationId, and eventId cannot be null");
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        return SagaStep.builder()
                .name("reserveStock")
                .topic(EventTopics.STOCK_RESERVED.getTopic())
                .action(() -> inventoryService.reserveStock(orderId, quantity)
                        .timeout(SAGA_STEP_TIMEOUT)
                        .retryWhen(createTransientErrorRetrySpec("reserveStock")))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity)
                        .timeout(SAGA_STEP_TIMEOUT)
                        .retryWhen(createTransientErrorRetrySpec("releaseStock-compensation")))
                .successEvent(eventSuccessId -> new StockReservedEvent(orderId, correlationId, eventId, externalReference,quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();
    }

    /**
     * Actualiza el estado de una orden con validación y manejo de errores mejorado
     */
    protected Mono<Order> updateOrderStatus(Long orderId, String status, String correlationId) {
        if (orderId == null || status == null || correlationId == null) {
            return Mono.error(new IllegalArgumentException("orderId, status, and correlationId cannot be null"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "status", status
        );

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.info("Updating order {} status to {}", orderId, status);

            // Crear un tag para métrica de tiempo
            Tag[] tags = new Tag[] {
                    Tag.of("status", status),
                    Tag.of("correlation_id", correlationId)
            };

            Timer.Sample timer = Timer.start(meterRegistry);

            return databaseClient.sql("UPDATE orders SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                    .bind("status", status)
                    .bind("id", orderId)
                    .then()
                    .doOnSuccess(v -> {
                        log.info("Updated order {} status to {}", orderId, status);
                        timer.stop(meterRegistry.timer("saga.order.status.update", Tags.of(tags))); // Usar Tags.of()
                    })
                    .doOnError(e -> {
                        log.error("Failed to update order {} status: {}", orderId, e.getMessage(), e);
                        meterRegistry.counter("saga.order.status.error", Tags.of(tags)).increment(); // Usar Tags.of()
                    })
                    .timeout(DB_OPERATION_TIMEOUT)
                    .retryWhen(createDatabaseRetrySpec("updateOrderStatus"))
                    .then(insertUpdateStatusAuditLog(orderId, status, correlationId))
                    .then(Mono.just(new Order(orderId, status, correlationId)))
                    .transform(resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS));
        });
    }

    /**
     * Insertar registro de auditoría para actualización de estado
     */
    protected Mono<Void> insertUpdateStatusAuditLog(Long orderId, String status, String correlationId) {
        return databaseClient.sql(
                        "INSERT INTO order_status_history (order_id, status, correlation_id, timestamp) VALUES (:orderId, :status, :correlationId, CURRENT_TIMESTAMP)")
                .bind("orderId", orderId)
                .bind("status", status)
                .bind("correlationId", correlationId)
                .then()
                .onErrorResume(e -> {
                    log.warn("Failed to insert status audit log, continuing: {}", e.getMessage());
                    return Mono.empty(); // No interrumpimos el flujo principal
                });
    }

    /**
     * Clasificación de tipos de errores para decisiones de reintento y compensación
     */
    protected enum ErrorType {
        TRANSIENT,       // Errores temporales que podrían resolverse con reintentos
        VALIDATION,      // Errores de validación o lógica de negocio
        RESOURCE,        // Errores permanentes de recursos
        COMMUNICATION,   // Errores de comunicación con servicios externos
        UNKNOWN          // Errores no clasificados
    }

    /**
     * Clasificar el tipo de error para estrategia de manejo
     */
    protected ErrorType classifyError(Throwable e) {
        if (e instanceof java.util.concurrent.TimeoutException ||
                e instanceof java.net.ConnectException ||
                e instanceof io.r2dbc.spi.R2dbcTransientResourceException) {
            return ErrorType.TRANSIENT;
        } else if (e instanceof IllegalArgumentException ||
                e instanceof IllegalStateException ||
                e instanceof java.util.NoSuchElementException) {
            return ErrorType.VALIDATION;
        } else if (e instanceof io.r2dbc.spi.R2dbcNonTransientResourceException) {
            return ErrorType.RESOURCE;
        } else if (e instanceof java.net.SocketException ||
                e instanceof reactor.netty.http.client.PrematureCloseException) {
            return ErrorType.COMMUNICATION;
        } else {
            return ErrorType.UNKNOWN;
        }
    }

    /**
     * Ejecuta compensación con manejo de errores y reintentos
     */
    protected Mono<Void> executeRobustCompensation(SagaStep step, CompensationManager compensationManager) {
        if (step == null || step.getCompensation() == null) {
            log.warn("No compensation defined for step {}",
                    step != null ? step.getName() : "null");
            return Mono.empty();
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "stepName", step.getName(),
                "orderId", step.getOrderId().toString(),
                "correlationId", step.getCorrelationId(),
                "eventId", step.getEventId(),
                "operation", "compensation"
        );

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.info("Executing compensation for step {} of order {}",
                    step.getName(), step.getOrderId());

            Timer.Sample timer = Timer.start(meterRegistry);

            return insertCompensationLog(step, "STARTED")
                    .then(compensationManager.executeCompensation(step))
                    .timeout(SAGA_STEP_TIMEOUT)
                    .doOnSuccess(v -> {
                        log.info("Compensation completed successfully for step {}", step.getName());
                        timer.stop(meterRegistry.timer("saga.compensation",
                                "step", step.getName(),
                                "result", "success"));
                    })
                    .doOnError(e -> {
                        log.error("Compensation failed for step {}: {}",
                                step.getName(), e.getMessage(), e);
                        meterRegistry.counter("saga.compensation.error",
                                "step", step.getName()).increment();
                    })
                    .then(insertCompensationLog(step, "COMPLETED"))
                    .onErrorResume(e -> {
                        return insertCompensationLog(step, "FAILED: " + e.getMessage())
                                .then(triggerCompensationFailureAlert(step, e))
                                .then(Mono.empty()); // No propagamos el error para no bloquear
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    /**
     * Crea una especificación de reintento para errores transitorios en base de datos
     */
    protected Retry createDatabaseRetrySpec(String operation) {
        return Retry.backoff(MAX_DB_RETRIES, INITIAL_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .filter(e -> classifyError(e) == ErrorType.TRANSIENT)
                .doBeforeRetry(retrySignal -> {
                    log.warn("Retrying database operation {} after error, attempt: {}/{}",
                            operation, retrySignal.totalRetries() + 1, MAX_DB_RETRIES);
                    meterRegistry.counter("saga.db.retry",
                            "operation", operation).increment();
                });
    }

    /**
     * Crea una especificación de reintento para errores transitorios en operaciones generales
     */
    protected Retry createTransientErrorRetrySpec(String operation) {
        return Retry.backoff(MAX_EVENT_RETRIES, INITIAL_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .filter(e -> classifyError(e) == ErrorType.TRANSIENT ||
                        classifyError(e) == ErrorType.COMMUNICATION)
                .doBeforeRetry(retrySignal -> {
                    log.warn("Retrying operation {} after error, attempt: {}/{}",
                            operation, retrySignal.totalRetries() + 1, MAX_EVENT_RETRIES);
                    meterRegistry.counter("saga.operation.retry",
                            "operation", operation).increment();
                });
    }

    /**
     * Registra log de compensación en base de datos
     */
    protected Mono<Void> insertCompensationLog(SagaStep step, String status) {
        return databaseClient.sql(
                        "INSERT INTO compensation_log (step_name, order_id, correlation_id, event_id, status, timestamp) " +
                                "VALUES (:stepName, :orderId, :correlationId, :eventId, :status, CURRENT_TIMESTAMP)")
                .bind("stepName", step.getName())
                .bind("orderId", step.getOrderId())
                .bind("correlationId", step.getCorrelationId())
                .bind("eventId", step.getEventId())
                .bind("status", status)
                .then()
                .onErrorResume(e -> {
                    log.error("Failed to log compensation status: {}", e.getMessage());
                    return Mono.empty(); // No interrumpimos el flujo principal
                });
    }

    /**
     * Registra un fallo de paso en base de datos para análisis posterior
     */
    protected Mono<Void> recordStepFailure(SagaStep step, Throwable error, ErrorType errorType) {
        return databaseClient.sql(
                        "INSERT INTO saga_step_failures (step_name, order_id, correlation_id, event_id, " +
                                "error_message, error_type, error_category, timestamp) " +
                                "VALUES (:stepName, :orderId, :correlationId, :eventId, :errorMessage, :errorType, :errorCategory, CURRENT_TIMESTAMP)")
                .bind("stepName", step.getName())
                .bind("orderId", step.getOrderId())
                .bind("correlationId", step.getCorrelationId())
                .bind("eventId", step.getEventId())
                .bind("errorMessage", error.getMessage())
                .bind("errorType", error.getClass().getName())
                .bind("errorCategory", errorType.name())
                .then()
                .onErrorResume(e -> {
                    log.error("Failed to record step failure: {}", e.getMessage());
                    return Mono.empty(); // No interrumpimos el flujo principal
                });
    }

    /**
     * Envía alerta para fallo de compensación que requiere intervención
     */
    protected Mono<Void> triggerCompensationFailureAlert(SagaStep step, Throwable error) {
        String alertMessage = String.format(
                "CRITICAL: Compensation failed for step %s, order %s: %s [%s]",
                step.getName(), step.getOrderId(), error.getMessage(), error.getClass().getSimpleName());

        log.error(alertMessage);

        // Aquí se implementaría integración real con sistema de alertas
        // Para este ejemplo solo incrementamos métricas
        meterRegistry.counter("saga.critical.compensation.failure",
                "step", step.getName()).increment();

        return Mono.empty(); // Placeholder para futura implementación
    }

    /**
     * Inserta datos de una orden en la base de datos con validaciones reforzadas
     */
    protected Mono<Void> insertOrderData(Long orderId, String correlationId, String eventId, OrderEvent event) {
        if (orderId == null || correlationId == null || eventId == null || event == null) {
            return Mono.error(new IllegalArgumentException("Required parameters cannot be null"));
        }

        return databaseClient.sql(
                        "INSERT INTO orders (id, status, correlation_id, created_at, updated_at) " +
                                "VALUES (:id, :status, :correlationId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                .bind("id", orderId)
                .bind("status", "pending")
                .bind("correlationId", correlationId)
                .then()
                .doOnSuccess(v -> log.info("Inserted order {} into orders table", orderId))
                .doOnError(e -> log.error("Failed to insert order {} into table: {}",
                        orderId, e.getMessage(), e))
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createDatabaseRetrySpec("insertOrder"))
                .then(databaseClient.sql(
                                "INSERT INTO outbox (event_type, correlation_id, event_id, payload, created_at, status) " +
                                        "VALUES (:event_type, :correlationId, :eventId, :payload, CURRENT_TIMESTAMP, 'PENDING')")
                        .bind("event_type", event.getType().name())
                        .bind("correlationId", correlationId)
                        .bind("eventId", eventId)
                        .bind("payload", event.toJson())
                        .then())
                .doOnSuccess(v -> log.info("Inserted outbox event for order {}", orderId))
                .doOnError(e -> log.error("Failed to insert outbox event for order {}: {}",
                        orderId, e.getMessage(), e))
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createDatabaseRetrySpec("insertOutbox"))
                .then(databaseClient.sql(
                                "INSERT INTO processed_events (event_id, processed_at) VALUES (:eventId, CURRENT_TIMESTAMP)")
                        .bind("eventId", eventId)
                        .then())
                .doOnSuccess(v -> log.info("Inserted processed event for order {}", orderId))
                .doOnError(e -> log.error("Failed to insert processed event for order {}: {}",
                        orderId, e.getMessage(), e))
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createDatabaseRetrySpec("insertProcessedEvent"))
                .transform(resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS));
    }

    /**
     * Verifica si un evento ya ha sido procesado para garantizar idempotencia
     */
    protected Mono<Boolean> isEventAlreadyProcessed(String eventId) {
        if (eventId == null) {
            return Mono.error(new IllegalArgumentException("eventId cannot be null"));
        }

        return databaseClient.sql("SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId")
                .bind("eventId", eventId)
                .map(row -> row.get(0, Integer.class))
                .one()
                .map(count -> count > 0)
                .defaultIfEmpty(false) // Si no hay resultados, no está procesado
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createDatabaseRetrySpec("checkEventProcessed"))
                .doOnSuccess(processed -> {
                    if (processed) {
                        log.info("Event {} already processed", eventId);
                    }
                })
                .onErrorResume(e -> {
                    log.error("Error checking if event is processed: {}", e.getMessage(), e);
                    return Mono.just(false); // Por defecto asumimos que no está procesado
                });
    }

    /**
     * Encuentra una orden existente por ID
     */
    protected Mono<Order> findExistingOrder(Long orderId) {
        if (orderId == null) {
            return Mono.error(new IllegalArgumentException("orderId cannot be null"));
        }

        return databaseClient.sql("SELECT id, status, correlation_id FROM orders WHERE id = :id")
                .bind("id", orderId)
                .map(row -> new Order(
                        row.get("id", Long.class),
                        row.get("status", String.class),
                        row.get("correlation_id", String.class)
                ))
                .one()
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createDatabaseRetrySpec("findExistingOrder"))
                .switchIfEmpty(Mono.error(new IllegalStateException("Order not found: " + orderId)));
    }

    /**
     * Maneja un error al crear una orden con clasificación de errores
     */
    protected Mono<Order> handleCreateOrderError(Long orderId, String correlationId, String eventId, String externalReference, Throwable e) {
        ErrorType errorType = classifyError(e);

        log.error("Error creating order {}: {} [Type: {}]",
                orderId, e.getMessage(), errorType, e);

        meterRegistry.counter("saga.order.creation.error",
                "error_type", errorType.name(),
                "error_class", e.getClass().getSimpleName()).increment();

        OrderFailedEvent failedEvent = new OrderFailedEvent(
                orderId,
                correlationId,
                eventId,
                "createOrder",
                String.format("%s: %s [Type: %s]", e.getClass().getSimpleName(), e.getMessage(), errorType),
                externalReference);

        return publishFailedEvent(failedEvent)
                .then(recordStepFailure(createDummyStepForError("createOrder", orderId, correlationId, eventId), e, errorType))
                .then(Mono.just(new Order(orderId, "failed", correlationId)));
    }

    /**
     * Crea un paso dummy para registro de errores en operaciones que no son pasos
     */
    protected SagaStep createDummyStepForError(String operationName, Long orderId, String correlationId, String eventId) {
        return SagaStep.builder()
                .name(operationName)
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();
    }

    /**
     * Publica un evento en el sistema con manejo de errores mejorado
     */
    protected Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        if (event == null || step == null || topic == null) {
            return Mono.error(new IllegalArgumentException("event, step, and topic cannot be null"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "eventId", event.getEventId(),
                "eventType", event.getType().name(),
                "step", step,
                "topic", topic,
                "orderId", event.getOrderId().toString(),
                "correlationId", event.getCorrelationId()
        );

        Tag[] tags = new Tag[] {
                Tag.of("step", step),
                Tag.of("topic", topic),
                Tag.of("event_type", event.getType().name())
        };

        Timer.Sample timer = Timer.start(meterRegistry);

        return ReactiveUtils.withContextAndMetrics(
                context,
                () -> {
                    log.info("Publishing {} event {} for step {} to topic {}",
                            event.getType(), event.getEventId(), step, topic);

                    return saveEventHistory(event, step, "ATTEMPT")
                            .then(eventPublisher.publishEvent(event, step, topic)
                                    .map(EventPublishOutcome::getEvent)
                                    .timeout(EVENT_PUBLISH_TIMEOUT)
                                    .retryWhen(createTransientErrorRetrySpec("publish-" + event.getType()))
                                    .transform(resilienceManager.applyResilience(CircuitBreakerCategory.EVENT_PUBLISHING))
                                    .doOnSuccess(v -> {
                                        log.info("Published event {} for step {}", event.getEventId(), step);
                                        saveEventHistory(event, step, "SUCCESS").subscribe();
                                        timer.stop(meterRegistry.timer("saga.event.publish.time", Tags.of(tags))); // Usar Tags.of()
                                    })
                                    .doOnError(e -> {
                                        log.error("Failed to publish event {} for step {}: {}",
                                                event.getEventId(), step, e.getMessage(), e);
                                        saveEventHistory(event, step, "FAILURE: " + e.getMessage()).subscribe();
                                        meterRegistry.counter("saga.event.publish.error", Tags.of(tags)).increment(); // Usar Tags.of()
                                    }));
                },
                meterRegistry,
                SagaConfig.METRIC_EVENT_PUBLISH,
                tags
        );
    }

    /**
     * Guarda historial de eventos para auditoría
     */
    protected Mono<Void> saveEventHistory(OrderEvent event, String operation, String outcome) {
        return databaseClient.sql(
                        "INSERT INTO event_history (event_id, correlation_id, order_id, event_type, " +
                                "operation, outcome, timestamp) VALUES " +
                                "(:eventId, :correlationId, :orderId, :eventType, :operation, :outcome, CURRENT_TIMESTAMP)")
                .bind("eventId", event.getEventId())
                .bind("correlationId", event.getCorrelationId())
                .bind("orderId", event.getOrderId())
                .bind("eventType", event.getType().name())
                .bind("operation", operation)
                .bind("outcome", outcome)
                .then()
                .onErrorResume(e -> {
                    log.warn("Failed to save event history: {}", e.getMessage());
                    return Mono.empty();  // No interrumpimos el flujo principal
                });
    }

    /**
     * Publica un evento de fallo
     */
    protected Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        return publishEvent(event, "failedEvent", EventTopics.ORDER_FAILED.getTopic())
                .onErrorResume(e -> {
                    log.error("Failed to publish failure event: {}", e.getMessage(), e);
                    // Métrica crítica - fallo al publicar evento de fallo
                    meterRegistry.counter("saga.critical.event.failure").increment();
                    return Mono.empty(); // No propagamos el error para no bloquear la compensación
                })
                .then();
    }
}