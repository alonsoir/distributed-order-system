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

    protected BaseSagaOrchestrator(
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
     * Crea un paso de reserva de stock
     */
    protected SagaStep createReserveStockStep(
            InventoryService inventoryService,
            Long orderId,
            int quantity,
            String correlationId,
            String eventId) {
        return SagaStep.builder()
                .name("reserveStock")
                .topic(EventTopics.STOCK_RESERVED.getTopic())
                .action(() -> inventoryService.reserveStock(orderId, quantity))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                .successEvent(eventSuccessId -> new StockReservedEvent(orderId, correlationId, eventId, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();
    }

    /**
     * Actualiza el estado de una orden
     */
    protected Mono<Order> updateOrderStatus(Long orderId, String status, String correlationId) {
        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "status", status
        );

        return ReactiveUtils.withDiagnosticContext(context, () ->
                databaseClient.sql("UPDATE orders SET status = :status WHERE id = :id")
                        .bind("status", status)
                        .bind("id", orderId)
                        .then()
                        .doOnSuccess(v -> log.info("Updated order {} status to {}", orderId, status))
                        .doOnError(e -> log.error("Failed to update order {} status: {}", orderId, e.getMessage(), e))
                        .thenReturn(new Order(orderId, status, correlationId))
                        .transform(resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS))
        );
    }

    /**
     * Maneja un error en un paso de saga
     */
    protected Mono<OrderEvent> handleStepError(SagaStep step, Throwable e, CompensationManager compensationManager) {
        OrderFailedEvent failedEvent = new OrderFailedEvent(
                step.getOrderId(),
                step.getCorrelationId(),
                step.getEventId(),
                step.getName(),
                String.format("%s: %s", e.getClass().getSimpleName(), e.getMessage()));

        return publishFailedEvent(failedEvent)
                .then(compensationManager.executeCompensation(step))
                .then(Mono.error(e));
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
    protected Mono<Order> handleCreateOrderError(Long orderId, String correlationId, String eventId, Throwable e) {
        log.error("Transactional error in createOrder for order {}: {}", orderId, e.getMessage(), e);
        OrderFailedEvent failedEvent = new OrderFailedEvent(
                orderId, correlationId, eventId, "createOrder",
                String.format("%s: %s", e.getClass().getSimpleName(), e.getMessage()));
        return publishFailedEvent(failedEvent)
                .then(Mono.just(new Order(orderId, "failed", correlationId)));
    }

    /**
     * Publica un evento en el sistema
     */
    protected Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
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

        return ReactiveUtils.withContextAndMetrics(
                context,
                () -> {
                    log.info("Publishing {} event {} for step {} to topic {}",
                            event.getType(), event.getEventId(), step, topic);

                    return eventPublisher.publishEvent(event, step, topic)
                            .map(EventPublishOutcome::getEvent)
                            .transform(resilienceManager.applyResilience(CircuitBreakerCategory.EVENT_PUBLISHING))
                            .doOnSuccess(v -> log.info("Published event {} for step {}",
                                    event.getEventId(), step))
                            .doOnError(e -> log.error("Failed to publish event {} for step {}: {}",
                                    event.getEventId(), step, e.getMessage(), e));
                },
                meterRegistry,
                SagaConfig.METRIC_EVENT_PUBLISH,
                tags
        );
    }

    /**
     * Publica un evento de fallo
     */
    protected Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        return publishEvent(event, "failedEvent", EventTopics.ORDER_FAILED.getTopic()).then();
    }
}