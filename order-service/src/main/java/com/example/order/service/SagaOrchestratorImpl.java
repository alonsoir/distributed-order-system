package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.events.StockReservedEvent;
import com.example.order.events.OrderCreatedEvent;
import com.example.order.model.SagaStep;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class SagaOrchestratorImpl implements SagaOrchestrator{
    private static final Logger log = LoggerFactory.getLogger(SagaOrchestratorImpl.class);
    private final DatabaseClient databaseClient;
    private final InventoryService inventoryService;
    @Qualifier("orderEventPublisher")
    private final EventPublisher eventPublisher;
    private final CompensationManager compensationManager;
    private final TransactionalOperator transactionalOperator;
    private final MeterRegistry meterRegistry;
    private final IdGenerator idGenerator;

    public Mono<Order> executeOrderSaga(int quantity, double amount ) {

        Long orderId = idGenerator.generateOrderId();
        String eventId = idGenerator.generateEventId();
        String correlationId = idGenerator.generateCorrelationId();

        return createOrder(orderId, correlationId, eventId)
                .flatMap(order -> executeStep(SagaStep.builder()
                        .name("reserveStock")
                        .topic(EventTopics.STOCK_RESERVED.getTopic())
                        .action(() -> inventoryService.reserveStock(orderId, quantity))
                        .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                        .successEvent(eventsuccesId -> new StockReservedEvent(orderId, correlationId, eventId, quantity))
                        .orderId(orderId)
                        .correlationId(correlationId)
                        .eventId(eventId)
                        .build()))
                .flatMap(event -> databaseClient.sql("UPDATE orders SET status = :status WHERE id = :id")
                        .bind("status", "completed")
                        .bind("id", orderId)
                        .then()
                        .thenReturn(new Order(orderId, "completed", correlationId)))
                .onErrorResume(e -> databaseClient.sql("UPDATE orders SET status = :status WHERE id = :id")
                        .bind("status", "failed")
                        .bind("id", orderId)
                        .then()
                        .thenReturn(new Order(orderId, "failed", correlationId)));
    }

    private Mono<OrderEvent> executeStep(SagaStep step) {
        log.info("Executing step {} for order {} correlationId {}", step.getName(), step.getOrderId(), step.getCorrelationId());
        Mono<OrderEvent> stepMono = step.getAction().get()
                .then(Mono.defer(() -> publishEvent(step.getSuccessEvent().apply(step.getEventId()), step.getName(), step.getTopic())))
                .doOnSuccess(event -> log.info("Step {} completed for order {}", step.getName(), step.getOrderId()))
                .doOnError(e -> log.error("Error in step {} for order {}: {}", step.getName(), step.getOrderId(), e.getMessage(), e));

        return transactionalOperator.transactional(stepMono)
                .doOnSuccess(event -> meterRegistry.counter("saga_step_success", "step", step.getName()).increment())
                .onErrorResume(e -> {
                    log.error("Step {} failed for order {}: {}", step.getName(), step.getOrderId(), e.getMessage(), e);
                    meterRegistry.counter("saga_step_failed", "step", step.getName()).increment();
                    OrderFailedEvent failedEvent = new OrderFailedEvent(
                            step.getOrderId(),
                            step.getCorrelationId(),
                            step.getEventId(),
                            step.getName(),
                            e.getMessage());
                    return publishFailedEvent(failedEvent)
                            .then(compensationManager.executeCompensation(step)
                                    .then(Mono.error(e)));
                });
    }

    private Mono<Order> createOrder(Long orderId, String correlationId, String eventId) {

        OrderEvent event = new OrderCreatedEvent(orderId, correlationId, eventId, "pending");
        log.info("Creating order {} with correlationId {} and eventId {}", orderId, correlationId,eventId);
        Mono<Order> orderMono = databaseClient.sql("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :status, :correlationId)")
                .bind("id", orderId)
                .bind("status", "pending")
                .bind("correlationId", correlationId)
                .then()
                .doOnSuccess(v -> log.info("Inserted order {} into orders table", orderId))
                .then(databaseClient.sql("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)")
                        .bind("event_type", event.getType().name())
                        .bind("correlationId", correlationId)
                        .bind("eventId", eventId)
                        .bind("payload", event.toJson())
                        .then())
                .doOnSuccess(v -> log.info("Inserted outbox event for order {}", orderId))
                .then(databaseClient.sql("INSERT INTO processed_events (event_id) VALUES (:eventId)")
                        .bind("eventId", eventId)
                        .then())
                .doOnSuccess(v -> log.info("Inserted processed event for order {}", orderId))
                .then(publishEvent(event, "createOrder", EventTopics.ORDER_CREATED.getTopic()))
                .doOnSuccess(v -> log.info("Published event for order {}", orderId))
                .then(Mono.just(new Order(orderId, "pending", correlationId)))
                .doOnSuccess(v -> log.info("Created order object for {}", orderId))
                .doOnError(e -> log.error("Error in createOrder for order {}: {}", orderId, e.getMessage(), e));

        return transactionalOperator.transactional(orderMono)
                .doOnSuccess(v -> log.info("Order {} created correlationId {}", orderId, correlationId))
                .doOnError(e -> log.error("Transactional error in createOrder for order {}: {}", orderId, e.getMessage(), e))
                .onErrorResume(e -> {
                    log.error("Transactional error in createOrder for order {}: {}", orderId, e.getMessage(), e);
                    OrderFailedEvent failedEvent = new OrderFailedEvent(
                            orderId, correlationId, eventId, "createOrder", e.getMessage());
                    return publishFailedEvent(failedEvent)
                            .then(Mono.just(new Order(orderId, "failed", correlationId)));
                });
    }

    public Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        return publishEvent(event, "failedEvent", EventTopics.ORDER_FAILED.getTopic()).then();
    }

    public Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        return eventPublisher.publishEvent(event, step, topic)
                .map(EventPublishOutcome::getEvent)
                .doOnSuccess(v -> log.info("Published event {} for step {}", event.getEventId(), step))
                .doOnError(e -> log.error("Failed to publish event {} for step {}: {}", event.getEventId(), step, e.getMessage()));
    }
    public Mono<Void> createFailedEvent(String reason, String externalReference) {
        Long orderId = idGenerator.generateOrderId();
        String correlationId = idGenerator.generateCorrelationId();
        String eventId = idGenerator.generateEventId();

        OrderFailedEvent event = new OrderFailedEvent(orderId, correlationId, eventId, "processOrder", reason);
        return publishFailedEvent(event);
    }
}