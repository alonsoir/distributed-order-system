package com.example.order.service;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {
    private final DatabaseClient databaseClient;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final InventoryService inventoryService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry meterRegistry;
    private final TransactionalOperator transactionalOperator;

    interface OrderEvent {
        Long getOrderId();
        String getCorrelationId();
        String getEventId();
        String getType();
        String toJson();
    }

    record OrderCreatedEvent(Long orderId, String correlationId, String eventId, String status) implements OrderEvent {
        public String getType() { return "OrderCreated"; }
        public String toJson() { return "{\"orderId\":" + getOrderId() + ",\"correlationId\":\"" + getCorrelationId() + "\",\"eventId\":\"" + eventId + "\",\"status\":\"" + status + "\"}"; }
        @Override
        public Long getOrderId() { return orderId; }
        @Override
        public String getCorrelationId() { return correlationId; }
        public String getEventId() { return eventId; }
    }

    record StockReservedEvent(Long orderId, String correlationId, String eventId, int quantity) implements OrderEvent {
        public String getType() { return "StockReserved"; }
        public String toJson() { return "{\"orderId\":" + getOrderId() + ",\"correlationId\":\"" + getCorrelationId() + "\",\"eventId\":\"" + eventId + "\",\"quantity\":" + quantity + "}"; }
        @Override
        public Long getOrderId() { return orderId; }
        @Override
        public String getCorrelationId() { return correlationId; }
        public String getEventId() { return eventId; }
    }

    record OrderFailedEvent(Long orderId, String correlationId, String eventId, String step, String reason) implements OrderEvent {
        public String getType() { return "OrderFailed"; }
        public String toJson() { return "{\"orderId\":" + getOrderId() + ",\"correlationId\":\"" + getCorrelationId() + "\",\"eventId\":\"" + eventId + "\",\"step\":\"" + step + "\",\"reason\":\"" + reason + "\"}"; }
        @Override
        public Long getOrderId() { return orderId; }
        @Override
        public String getCorrelationId() { return correlationId; }
        public String getEventId() { return eventId; }
    }

    record CompensationTask(Long orderId, String correlationId, String step, String action, String error, int retries) {}

    @Builder
    static class SagaStep {
        String name;
        Supplier<Mono<?>> action;
        Supplier<Mono<?>> compensation;
        java.util.function.Function<String, OrderEvent> successEvent;
        Long orderId;
        String correlationId;
        String eventId;
    }

    public Mono<Order> processOrder(Long orderId, int quantity, double amount) {
        String correlationId = UUID.randomUUID().toString();
        log.info("Starting order {} with correlationId {}", orderId, correlationId);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("orderProcessing");

        Mono<Order> orderMono = executeOrderSaga(orderId, quantity, amount, correlationId)
                .timeout(Duration.ofSeconds(15))
                .onErrorResume(e -> onTimeout(orderId, correlationId, "global_timeout"));

        return orderMono.transform(CircuitBreakerOperator.of(circuitBreaker))
                .doOnSuccess(order -> meterRegistry.counter("orders_success").increment())
                .doOnError(e -> meterRegistry.counter("orders_failed").increment())
                .onErrorResume(e -> {
                    log.error("Circuit breaker tripped for order {}: {}", orderId, e.getMessage());
                    return publishFailedEvent(orderId, correlationId, "circuit_breaker", e.getMessage())
                            .then(Mono.just(fallbackOrder(orderId)));
                });
    }

    private Mono<Order> executeOrderSaga(Long orderId, int quantity, double amount, String correlationId) {
        return createOrder(orderId, correlationId)
                .flatMap(order -> executeStep(
                        SagaStep.builder()
                                .name("reserveStock")
                                .action(() -> inventoryService.reserveStock(orderId, quantity))
                                .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                                .successEvent(eventId -> new StockReservedEvent(orderId, correlationId, eventId, quantity))
                                .orderId(orderId)
                                .correlationId(correlationId)
                                .eventId(UUID.randomUUID().toString())
                                .build()))
                .map(event -> new Order(orderId, "completed", correlationId));
    }

    Mono<OrderEvent> executeStep(SagaStep step) {
        log.info("Executing step {} for order {} correlationId {}", step.name, step.orderId, step.correlationId);
        return step.action.get()
                .flatMap(result -> {
                    OrderEvent event = step.successEvent.apply(step.eventId);
                    return databaseClient.sql("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)")
                            .bind("event_type", event.getType())
                            .bind("correlationId", step.correlationId)
                            .bind("eventId", step.eventId)
                            .bind("payload", event.toJson())
                            .then()
                            .then(databaseClient.sql("INSERT INTO processed_events (event_id) VALUES (:eventId)")
                                    .bind("eventId", step.eventId)
                                    .then())
                            .then(publishEvent(event))
                            .then(Mono.just(event));
                })
                .as(transactionalOperator::transactional)
                .doOnSuccess(event -> meterRegistry.counter("saga_step_success", "step", step.name).increment())
                .onErrorResume(e -> {
                    log.error("Step {} failed for order {}: {}", step.name, step.orderId, e.getMessage());
                    meterRegistry.counter("saga_step_failed", "step", step.name).increment();
                    return publishFailedEvent(step.orderId, step.correlationId, step.name, e.getMessage())
                            .then(step.compensation.get()
                                    .doOnSuccess(c -> log.info("Compensation {} executed for order {}", step.name, step.orderId))
                                    .onErrorResume(compE -> {
                                        log.error("Compensation {} failed for order {}: {}", step.name, step.orderId, compE.getMessage());
                                        return redisTemplate.opsForList()
                                                .leftPush("failed-compensations",
                                                        new CompensationTask(step.orderId, step.correlationId, step.name, step.name, compE.getMessage(), 0))
                                                .then(Mono.error(compE));
                                    })
                                    .then(Mono.error(e)));
                });
    }

    Mono<Order> createOrder(Long orderId, String correlationId) {
        String eventId = UUID.randomUUID().toString();
        OrderCreatedEvent event = new OrderCreatedEvent(orderId, correlationId, eventId, "pending");
        return databaseClient.sql("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :status, :correlationId)")
                .bind("id", orderId)
                .bind("status", "pending")
                .bind("correlationId", correlationId)
                .then()
                .then(databaseClient.sql("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)")
                        .bind("event_type", event.getType())
                        .bind("correlationId", correlationId)
                        .bind("eventId", eventId)
                        .bind("payload", event.toJson())
                        .then())
                .then(databaseClient.sql("INSERT INTO processed_events (event_id) VALUES (:eventId)")
                        .bind("eventId", eventId)
                        .then())
                .then(publishEvent(event))
                .then(Mono.just(new Order(orderId, "pending", correlationId)))
                .as(transactionalOperator::transactional)
                .doOnSuccess(v -> log.info("Order {} created correlationId {}", orderId, correlationId))
                .onErrorResume(e ->
                        publishFailedEvent(orderId, correlationId, "createOrder", e.getMessage())
                                .then(Mono.just(fallbackOrder(orderId))));
    }

    private Mono<Void> publishEvent(OrderEvent event) {
        CircuitBreaker redisCircuitBreaker = circuitBreakerRegistry.circuitBreaker("redisOperations");
        Map<String, Object> eventMap = new HashMap<>();
        eventMap.put("orderId", event.getOrderId());
        eventMap.put("correlationId", event.getCorrelationId());
        eventMap.put("eventId", event.getEventId());
        eventMap.put("type", event.getType());
        eventMap.put("payload", event.toJson());

        Mono<Void> publishToRedis = redisTemplate.opsForStream()
                .add("orders", eventMap)
                .then()
                .doOnSuccess(record -> log.info("Published event {} for order {}", event.getType(), event.getOrderId()))
                .doOnError(e -> log.error("Failed to publish event {} for order {}: {}", event.getType(), event.getOrderId(), e.getMessage()));

        return publishToRedis.transform(CircuitBreakerOperator.of(redisCircuitBreaker))
                .onErrorResume(throwable -> {
                    log.error("Redis circuit breaker tripped for event {}: {}", event.getType(), throwable.getMessage());
                    return databaseClient.sql("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)")
                            .bind("event_type", event.getType())
                            .bind("correlationId", event.getCorrelationId())
                            .bind("eventId", event.getEventId())
                            .bind("payload", event.toJson())
                            .then()
                            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                                    .doAfterRetry(signal -> log.error("Retry attempt {} failed: {}", signal.totalRetries(), signal.failure()))
                                    .onRetryExhaustedThrow((retrySpec, retrySignal) ->
                                            new RuntimeException("Retry exhausted after 3 attempts", retrySignal.failure())))
                            .doOnSuccess(v -> log.info("Persisted event {} to outbox for order {}", event.getType(), event.getOrderId()))
                            .doOnError(e -> log.error("Failed to persist event {} to outbox for order {}: {}", event.getType(), event.getOrderId(), e.getMessage()));
                });
    }

    private Mono<Void> publishFailedEvent(Long orderId, String correlationId, String step, String reason) {
        String eventId = UUID.randomUUID().toString();
        OrderFailedEvent event = new OrderFailedEvent(orderId, correlationId, eventId, step, reason);
        log.info("Publishing OrderFailedEvent: {}", event.toJson());
        return publishEvent(event);
    }

    private Order fallbackOrder(Long orderId) {
        log.warn("Returning fallback order for {}", orderId);
        return new Order(orderId, "failed", "unknown");
    }

    private Mono<Order> onTimeout(Long orderId, String correlationId, String reason) {
        log.error("Timeout for order {}: {}", orderId, reason);
        return publishFailedEvent(orderId, correlationId, "timeout", reason)
                .then(Mono.just(fallbackOrder(orderId)));
    }
}