package com.example.order.service;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.config.SagaConfig;
import com.example.order.domain.Order;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.events.StockReservedEvent;
import com.example.order.model.SagaStep;
import com.example.order.model.SagaStepType;
import com.example.order.repository.EventRepository;
import com.example.order.resilience.ResilienceManager;
import com.example.order.utils.ReactiveUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Implementación base para orquestadores de sagas con funcionalidad común
 */
public abstract class BaseSagaOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(BaseSagaOrchestrator.class);

    protected final DatabaseClient databaseClient;
    protected final TransactionalOperator transactionalOperator;
    protected final MeterRegistry meterRegistry;
    protected final IdGenerator idGenerator;
    protected final ResilienceManager resilienceManager;
    protected final EventPublisher eventPublisher;
    protected final EventRepository eventRepository;

    protected BaseSagaOrchestrator(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            EventPublisher eventPublisher,
            EventRepository eventRepository) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = transactionalOperator;
        this.meterRegistry = meterRegistry;
        this.idGenerator = idGenerator;
        this.resilienceManager = resilienceManager;
        this.eventPublisher = eventPublisher;
        this.eventRepository = eventRepository;
    }

    /**
     * Crea un paso de reserva de stock
     */
    protected SagaStep createReserveStockStep(
            InventoryService inventoryService,
            Long orderId,
            int quantity,
            String correlationId,
            String eventId,
            String externalReference) {
        return SagaStep.builder()
                .name("reserveStock")
                .topic(EventTopics.STOCK_RESERVED.getTopic())
                .action(() -> inventoryService.reserveStock(orderId, quantity))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                .successEvent(eventSuccessId -> new StockReservedEvent(orderId, correlationId, eventId, externalReference,quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();
    }


    /**
     * Inserta datos de una orden en la base de datos
     */
    protected Mono<Void> insertOrderData(Long orderId, String correlationId, String eventId, OrderEvent event) {
        return databaseClient.sql("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :status, :correlationId)")
                .bind("id", orderId)
                .bind("status", "pending")
                .bind("correlationId", correlationId)
                .then()
                .doOnSuccess(v -> log.info("Inserted order {} into orders table", orderId))
                .doOnError(e -> log.error("Failed to insert order {} into table: {}",
                        orderId, e.getMessage(), e))
                .then(databaseClient.sql("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)")
                        .bind("event_type", event.getType().name())
                        .bind("correlationId", correlationId)
                        .bind("eventId", eventId)
                        .bind("payload", event.toJson())
                        .then())
                .doOnSuccess(v -> log.info("Inserted outbox event for order {}", orderId))
                .doOnError(e -> log.error("Failed to insert outbox event for order {}: {}",
                        orderId, e.getMessage(), e))
                .then(databaseClient.sql("INSERT INTO processed_events (event_id) VALUES (:eventId)")
                        .bind("eventId", eventId)
                        .then())
                .doOnSuccess(v -> log.info("Inserted processed event for order {}", orderId))
                .doOnError(e -> log.error("Failed to insert processed event for order {}: {}",
                        orderId, e.getMessage(), e))
                .transform(resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS));
    }

    /**
     * Maneja un error al crear una orden
     */
    protected Mono<Order> handleCreateOrderError(Long orderId,
                                                 String correlationId,
                                                 String eventId,
                                                 String externalReference,
                                                 Throwable e) {
        log.error("Transactional error in createOrder for order {}: {}", orderId, e.getMessage(), e);
        OrderFailedEvent failedEvent = new OrderFailedEvent(
                orderId,
                correlationId,
                eventId,
                SagaStepType.CREATE_ORDER,
                String.format("%s: %s", e.getClass().getSimpleName(), e.getMessage()),
                externalReference
                );
        return publishFailedEvent(failedEvent)
                .then(Mono.just(new Order(orderId, "failed", correlationId)));
    }


    /**
     * Actualiza el estado de una orden con registro en historial
     */
    protected Mono<Order> updateOrderStatus(Long orderId, String status, String correlationId) {
        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "status", status
        );

        return ReactiveUtils.withDiagnosticContext(context, () ->
                // Actualización atómica dentro de una transacción
                transactionalOperator.transactional(
                                databaseClient.sql("UPDATE orders SET status = :status, updated_at = NOW(), version = version + 1 WHERE id = :id")
                                        .bind("status", status)
                                        .bind("id", orderId)
                                        .then()
                                        .doOnSuccess(v -> log.info("Updated order {} status to {}", orderId, status))
                                        .doOnError(e -> log.error("Failed to update order {} status: {}", orderId, e.getMessage(), e))
                                        // Registrar el cambio de estado en la tabla de historial
                                        .then(databaseClient.sql("INSERT INTO order_status_history (order_id, status, correlation_id, timestamp) VALUES (:orderId, :status, :correlationId, NOW())")
                                                .bind("orderId", orderId)
                                                .bind("status", status)
                                                .bind("correlationId", correlationId)
                                                .then()
                                                .doOnSuccess(v -> log.debug("Recorded status history for order {}", orderId))
                                                .doOnError(e -> log.error("Failed to record status history for order {}: {}", orderId, e.getMessage(), e))
                                        )
                                        .thenReturn(new Order(orderId, status, correlationId))
                        )
                        .transform(resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS))
        );
    }

    /**
     * Maneja un error en un paso de saga con registro detallado
     */
    protected Mono<OrderEvent> handleStepError(SagaStep step, Throwable e, CompensationManager compensationManager) {
        String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
        String errorType = e.getClass().getSimpleName();

        Map<String, String> context = ReactiveUtils.createContext(
                "stepName", step.getName(),
                "orderId", step.getOrderId().toString(),
                "correlationId", step.getCorrelationId(),
                "eventId", step.getEventId(),
                "errorType", errorType,
                "errorMessage", errorMessage
        );

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.error("Step {} failed for order {}: {}", step.getName(), step.getOrderId(), errorMessage, e);

            // Crear el evento de fallo
            OrderFailedEvent failedEvent = new OrderFailedEvent(
                    step.getOrderId(),
                    step.getCorrelationId(),
                    step.getEventId(),
                    SagaStepType.FAILED_ORDER,
                    String.format("%s: %s", errorType, errorMessage),
                    step.getExternalReference());

            // Registrar el fallo en la tabla saga_step_failures
            return databaseClient.sql("INSERT INTO saga_step_failures " +
                            "(step_name, order_id, correlation_id, event_id, error_message, error_type, error_category, timestamp, handled) " +
                            "VALUES (:stepName, :orderId, :correlationId, :eventId, :errorMessage, :errorType, 'FUNCTIONAL', NOW(), false)")
                    .bind("stepName", step.getName())
                    .bind("orderId", step.getOrderId())
                    .bind("correlationId", step.getCorrelationId())
                    .bind("eventId", step.getEventId())
                    .bind("errorMessage", errorMessage)
                    .bind("errorType", errorType)
                    .then()
                    .doOnSuccess(v -> log.info("Recorded step failure in saga_step_failures"))
                    // Registrar en la tabla saga_failures si es necesario
                    .then(databaseClient.sql("INSERT INTO saga_failures " +
                                    "(order_id, correlation_id, error_message, error_type, error_category, timestamp, requires_attention) " +
                                    "VALUES (:orderId, :correlationId, :errorMessage, :errorType, 'FUNCTIONAL', NOW(), true)")
                            .bind("orderId", step.getOrderId())
                            .bind("correlationId", step.getCorrelationId())
                            .bind("errorMessage", errorMessage)
                            .bind("errorType", errorType)
                            .then()
                            .doOnSuccess(v -> log.info("Recorded in saga_failures table"))
                    )
                    // Publicar el evento de fallo
                    .then(publishFailedEvent(failedEvent))
                    // Registrar inicio de compensación
                    .then(databaseClient.sql("INSERT INTO compensation_log " +
                                    "(step_name, order_id, correlation_id, event_id, status, timestamp) " +
                                    "VALUES (:stepName, :orderId, :correlationId, :eventId, 'INITIATED', NOW())")
                            .bind("stepName", step.getName())
                            .bind("orderId", step.getOrderId())
                            .bind("correlationId", step.getCorrelationId())
                            .bind("eventId", step.getEventId())
                            .then()
                            .doOnSuccess(v -> log.info("Recorded compensation initiation for step {}", step.getName()))
                    )
                    // Ejecutar la compensación
                    .then(compensationManager.executeCompensation(step))
                    // Actualizar estado de la compensación a completado
                    .then(databaseClient.sql("UPDATE compensation_log " +
                                    "SET status = 'COMPLETED' " +
                                    "WHERE step_name = :stepName AND order_id = :orderId AND event_id = :eventId")
                            .bind("stepName", step.getName())
                            .bind("orderId", step.getOrderId())
                            .bind("eventId", step.getEventId())
                            .then()
                            .doOnSuccess(v -> log.info("Updated compensation status to COMPLETED"))
                    )
                    // Actualizar el estado del fallo a manejado
                    .then(databaseClient.sql("UPDATE saga_step_failures " +
                                    "SET handled = true " +
                                    "WHERE step_name = :stepName AND order_id = :orderId AND event_id = :eventId")
                            .bind("stepName", step.getName())
                            .bind("orderId", step.getOrderId())
                            .bind("eventId", step.getEventId())
                            .then()
                    )
                    .then(Mono.error(e)); // Propagamos el error original
        });
    }

    /**
     * Publica un evento en el sistema con registro detallado
     */
    protected Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        Map<String, String> context = ReactiveUtils.createContext(
                "eventId", event.getEventId(),
                "eventType", event.getType().name(),
                "step", step,
                "topic", topic,
                "orderId", event.getOrderId().toString(),
                "correlationId", event.getCorrelationId(),
                "externalReference", event.getExternalReference()
        );

        Tag[] tags = new Tag[] {
                Tag.of("step", step),
                Tag.of("topic", topic),
                Tag.of("event_type", event.getType().name())
        };

        return ReactiveUtils.withContextAndMetrics(
                context,
                () -> {
                    log.info("Publishing {} event {} for step {} to topic {}",
                            event.getType(), event.getEventId(), step, topic);

                    // Registrar intento de publicación en event_history
                    return databaseClient.sql("INSERT INTO event_history " +
                                    "(event_id, correlation_id, order_id, event_type, operation, outcome, timestamp) " +
                                    "VALUES (:eventId, :correlationId, :orderId, :eventType, :operation, 'ATTEMPT', NOW())")
                            .bind("eventId", event.getEventId())
                            .bind("correlationId", event.getCorrelationId())
                            .bind("orderId", event.getOrderId())
                            .bind("eventType", event.getType().name())
                            .bind("operation", step)
                            .then()
                            .then(eventPublisher.publishEvent(event, step, topic))
                            .map(EventPublishOutcome::getEvent)
                            .transform(resilienceManager.applyResilience(CircuitBreakerCategory.EVENT_PUBLISHING))
                            .doOnSuccess(v -> {
                                log.info("Published event {} for step {}", event.getEventId(), step);
                                // Registrar éxito en event_history de forma no bloqueante
                                databaseClient.sql("INSERT INTO event_history " +
                                                "(event_id, correlation_id, order_id, event_type, operation, outcome, timestamp) " +
                                                "VALUES (:eventId, :correlationId, :orderId, :eventType, :operation, 'SUCCESS', NOW())")
                                        .bind("eventId", event.getEventId())
                                        .bind("correlationId", event.getCorrelationId())
                                        .bind("orderId", event.getOrderId())
                                        .bind("eventType", event.getType().name())
                                        .bind("operation", step)
                                        .then()
                                        .subscribe();
                            })
                            .doOnError(e -> {
                                log.error("Failed to publish event {} for step {}: {}",
                                        event.getEventId(), step, e.getMessage(), e);
                                // Registrar fallo en event_history de forma no bloqueante
                                databaseClient.sql("INSERT INTO event_history " +
                                                "(event_id, correlation_id, order_id, event_type, operation, outcome, timestamp) " +
                                                "VALUES (:eventId, :correlationId, :orderId, :eventType, :operation, :outcome, NOW())")
                                        .bind("eventId", event.getEventId())
                                        .bind("correlationId", event.getCorrelationId())
                                        .bind("orderId", event.getOrderId())
                                        .bind("eventType", event.getType().name())
                                        .bind("operation", step)
                                        .bind("outcome", "ERROR: " + e.getMessage())
                                        .then()
                                        .subscribe();
                            });
                },
                meterRegistry,
                SagaConfig.METRIC_EVENT_PUBLISH,
                tags
        );
    }

    /**
     * Publica un evento de fallo con registro en tablas especializadas
     */
    protected Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        Map<String, String> context = ReactiveUtils.createContext(
                "eventId", event.getEventId(),
                "correlationId", event.getCorrelationId(),
                "orderId", event.getOrderId().toString(),
                "reason", event.getReason(),
                "step", SagaStepType.FAILED_EVENT.name(),
                "externalReference", event.getExternalReference()
        );

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.info("Publishing failure event for order {} with reason: {}",
                    event.getOrderId(), event.getReason());

            // Registrar en failed_events para seguimiento específico
            return databaseClient.sql("INSERT INTO failed_events " +
                            "(order_id, correlation_id, event_id, reason, external_reference, timestamp, handled) " +
                            "VALUES (:orderId, :correlationId, :eventId, :reason, :externalRef, NOW(), false)")
                    .bind("orderId", event.getOrderId())
                    .bind("correlationId", event.getCorrelationId())
                    .bind("eventId", event.getEventId())
                    .bind("reason", event.getReason())
                    .bind("externalRef", event.getExternalReference())
                    .then()
                    .doOnSuccess(v -> log.info("Recorded in failed_events for tracking"))
                    // Continuar con la publicación normal
                    .then(publishEvent(event, "failedEvent", EventTopics.ORDER_FAILED.getTopic()).then());
        });
    }

    /**
     * Verifica si un evento ya ha sido procesado (idempotencia)
     */
    /**
     * Verifica si un evento ya ha sido procesado (idempotencia)
     */
    protected Mono<Boolean> isEventProcessed(String eventId) {
        return databaseClient.sql("SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId")
                .bind("eventId", eventId)
                .map(row -> row.get(0, Long.class))
                .first()  // en lugar de single()
                .defaultIfEmpty(0L)  // por si no hay resultados
                .map(count -> count > 0L)  // conversión explícita a boolean
                .doOnSuccess(processed -> {
                    if (processed) {
                        log.info("Event {} has already been processed", eventId);
                    }
                });
    }

    /**
     * Registra un evento como procesado
     */
    protected Mono<Void> markEventAsProcessed(String eventId) {
        return databaseClient.sql("INSERT INTO processed_events (event_id, processed_at) " +
                        "VALUES (:eventId, NOW()) " +
                        "ON DUPLICATE KEY UPDATE processed_at = NOW()")
                .bind("eventId", eventId)
                .then()
                .doOnSuccess(v -> log.debug("Marked event {} as processed", eventId));
    }
}