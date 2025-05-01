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
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SagaOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);
    private final DatabaseClient databaseClient;
    private final InventoryService inventoryService;
    @Qualifier("orderEventPublisher")
    private final EventPublisher eventPublisher;
    private final CompensationManager compensationManager;
    private final TransactionalOperator transactionalOperator;
    private final MeterRegistry meterRegistry;

    public Mono<Order> executeOrderSaga(Long orderId, int quantity, double amount, String correlationId) {
        return createOrder(orderId, correlationId)
                .flatMap(order -> executeStep(SagaStep.builder()
                        .name("reserveStock")
                        .topic(EventTopics.STOCK_RESERVED.getTopic())
                        .action(() -> inventoryService.reserveStock(orderId, quantity))
                        .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                        .successEvent(eventId -> new StockReservedEvent(orderId, correlationId, eventId, quantity))
                        .orderId(orderId)
                        .correlationId(correlationId)
                        .eventId(UUID.randomUUID().toString())
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

    Mono<OrderEvent> executeStep(SagaStep step) {
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
                            step.getOrderId(), step.getCorrelationId(), UUID.randomUUID().toString(), step.getName(), e.getMessage());
                    return publishFailedEvent(failedEvent)
                            .then(compensationManager.executeCompensation(step)
                                    .then(Mono.error(e)));
                });
    }

    Mono<Order> createOrder(Long orderId, String correlationId) {
        String eventId = UUID.randomUUID().toString();
        OrderEvent event = new OrderCreatedEvent(orderId, correlationId, eventId, "pending");
        log.info("Creating order {} with correlationId {}", orderId, correlationId);
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
                            orderId, correlationId, UUID.randomUUID().toString(), "createOrder", e.getMessage());
                    return publishFailedEvent(failedEvent)
                            .then(Mono.just(new Order(orderId, "failed", correlationId)));
                });
    }

    public Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        return publishEvent(event, "failedEvent", EventTopics.ORDER_FAILED.getTopic()).then();
    }

    private Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        return eventPublisher.publishEvent(event, step, topic)
                .map(Result::getEvent)
                .doOnSuccess(v -> log.info("Published event {} for step {}", event.getEventId(), step))
                .doOnError(e -> log.error("Failed to publish event {} for step {}: {}", event.getEventId(), step, e.getMessage()));
    }
}