package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.events.StockReservedEvent;
import com.example.order.events.OrderCreatedEvent;
import com.example.order.model.SagaStep;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class SagaOrchestratorImpl implements SagaOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(SagaOrchestratorImpl.class);

    private final DatabaseClient databaseClient;
    private final InventoryService inventoryService;

    @Qualifier("orderEventPublisher")
    private final EventPublisher eventPublisher;

    private final CompensationManager compensationManager;
    private final TransactionalOperator transactionalOperator;
    private final MeterRegistry meterRegistry;
    private final IdGenerator idGenerator;

    // Añadimos registros para circuit breakers y bulkheads
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;

    // Cache para circuit breakers y bulkheads por paso
    private final Map<String, CircuitBreaker> stepCircuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, Bulkhead> stepBulkheads = new ConcurrentHashMap<>();

    @Override
    public Mono<Order> executeOrderSaga(int quantity, double amount) {
        Long orderId = idGenerator.generateOrderId();
        String eventId = idGenerator.generateEventId();
        String correlationId = idGenerator.generateCorrelationId();

        // Enriquecemos el contexto de diagnóstico
        Map<String, String> diagnosticContext = Map.of(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "eventId", eventId,
                "quantity", String.valueOf(quantity),
                "amount", String.valueOf(amount)
        );

        Timer.Sample sagaTimer = Timer.start(meterRegistry);

        // Comenzamos a medir la ejecución completa del saga
        return withDiagnosticContext(diagnosticContext, () -> {
            log.info("Starting order saga execution with quantity={}, amount={}", quantity, amount);

            return createOrder(orderId, correlationId, eventId)
                    .flatMap(order -> {
                        log.info("Order created, proceeding to reserve stock");
                        return executeStep(createReserveStockStep(orderId, quantity, correlationId, eventId));
                    })
                    .flatMap(event -> {
                        log.info("Stock reserved, updating order status to completed");
                        return updateOrderStatus(orderId, "completed", correlationId);
                    })
                    .doOnSuccess(order -> {
                        sagaTimer.stop(meterRegistry.timer("saga.execution.timer",
                                "status", "success",
                                "correlation_id", correlationId));
                        log.info("Order saga completed successfully");
                    })
                    .onErrorResume(e -> {
                        sagaTimer.stop(meterRegistry.timer("saga.execution.timer",
                                "status", "failed",
                                "error_type", e.getClass().getSimpleName(),
                                "correlation_id", correlationId));
                        log.error("Order saga failed: {}", e.getMessage(), e);
                        return updateOrderStatus(orderId, "failed", correlationId);
                    });
        });
    }

    private SagaStep createReserveStockStep(Long orderId, int quantity, String correlationId, String eventId) {
        return SagaStep.builder()
                .name("reserveStock")
                .topic(EventTopics.STOCK_RESERVED.getTopic())
                .action(() -> inventoryService.reserveStock(orderId, quantity))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                .successEvent(eventsuccesId -> new StockReservedEvent(orderId, correlationId, eventId, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();
    }

    private Mono<Order> updateOrderStatus(Long orderId, String status, String correlationId) {
        return databaseClient.sql("UPDATE orders SET status = :status WHERE id = :id")
                .bind("status", status)
                .bind("id", orderId)
                .then()
                .doOnSuccess(v -> log.info("Updated order {} status to {}", orderId, status))
                .doOnError(e -> log.error("Failed to update order {} status: {}", orderId, e.getMessage(), e))
                .thenReturn(new Order(orderId, status, correlationId));
    }

    @Override
    public Mono<OrderEvent> executeStep(SagaStep step) {
        if (step == null) {
            return Mono.error(new IllegalArgumentException("SagaStep cannot be null"));
        }

        log.info("Executing step {} for order {} correlationId {}", step.getName(), step.getOrderId(), step.getCorrelationId());

        // Obtenemos o creamos un circuit breaker específico para este paso
        CircuitBreaker circuitBreaker = getOrCreateStepCircuitBreaker(step.getName());

        // Obtenemos o creamos un bulkhead específico para este paso
        Bulkhead bulkhead = getOrCreateStepBulkhead(step.getName());

        // Enriquecemos el contexto de diagnóstico
        Map<String, String> diagnosticContext = Map.of(
                "stepName", step.getName(),
                "orderId", step.getOrderId().toString(),
                "correlationId", step.getCorrelationId(),
                "eventId", step.getEventId()
        );

        Timer.Sample stepTimer = Timer.start(meterRegistry);

        Mono<OrderEvent> stepMono = withDiagnosticContext(diagnosticContext, () -> {
            return step.getAction().get()
                    .then(Mono.defer(() -> {
                        log.info("Step action completed, publishing success event");
                        return publishEvent(step.getSuccessEvent().apply(step.getEventId()), step.getName(), step.getTopic());
                    }))
                    .doOnSuccess(event -> {
                        log.info("Step {} completed successfully for order {}", step.getName(), step.getOrderId());
                        meterRegistry.counter("saga_step_success", "step", step.getName()).increment();
                    })
                    .doOnError(e -> {
                        log.error("Step {} failed for order {}: {}", step.getName(), step.getOrderId(), e.getMessage(), e);
                        meterRegistry.counter("saga_step_failed", "step", step.getName()).increment();
                    });
        });

        // Aplicamos protección de circuit breaker y bulkhead
        return stepMono
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .as(transactionalOperator::transactional)
                .doOnSuccess(event -> {
                    stepTimer.stop(meterRegistry.timer("saga_step.timer",
                            "step", step.getName(),
                            "status", "success"));
                })
                .onErrorResume(e -> {
                    stepTimer.stop(meterRegistry.timer("saga_step.timer",
                            "step", step.getName(),
                            "status", "failed",
                            "error_type", e.getClass().getSimpleName()));

                    OrderFailedEvent failedEvent = new OrderFailedEvent(
                            step.getOrderId(),
                            step.getCorrelationId(),
                            step.getEventId(),
                            step.getName(),
                            String.format("%s: %s", e.getClass().getSimpleName(), e.getMessage()));

                    return publishFailedEvent(failedEvent)
                            .then(compensationManager.executeCompensation(step))
                            .then(Mono.error(e));
                });
    }

    @Override
    public Mono<Order> createOrder(Long orderId, String correlationId, String eventId) {
        // Enriquecemos el contexto de diagnóstico
        Map<String, String> diagnosticContext = Map.of(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "eventId", eventId
        );

        Timer.Sample createOrderTimer = Timer.start(meterRegistry);

        OrderEvent event = new OrderCreatedEvent(orderId, correlationId, eventId, "pending");
        log.info("Creating order {} with correlationId {} and eventId {}", orderId, correlationId, eventId);

        Mono<Order> orderMono = withDiagnosticContext(diagnosticContext, () -> {
            return databaseClient.sql("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :status, :correlationId)")
                    .bind("id", orderId)
                    .bind("status", "pending")
                    .bind("correlationId", correlationId)
                    .then()
                    .doOnSuccess(v -> log.info("Inserted order {} into orders table", orderId))
                    .doOnError(e -> log.error("Failed to insert order {} into table: {}", orderId, e.getMessage(), e))
                    .then(databaseClient.sql("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)")
                            .bind("event_type", event.getType().name())
                            .bind("correlationId", correlationId)
                            .bind("eventId", eventId)
                            .bind("payload", event.toJson())
                            .then())
                    .doOnSuccess(v -> log.info("Inserted outbox event for order {}", orderId))
                    .doOnError(e -> log.error("Failed to insert outbox event for order {}: {}", orderId, e.getMessage(), e))
                    .then(databaseClient.sql("INSERT INTO processed_events (event_id) VALUES (:eventId)")
                            .bind("eventId", eventId)
                            .then())
                    .doOnSuccess(v -> log.info("Inserted processed event for order {}", orderId))
                    .doOnError(e -> log.error("Failed to insert processed event for order {}: {}", orderId, e.getMessage(), e))
                    .then(publishEvent(event, "createOrder", EventTopics.ORDER_CREATED.getTopic()))
                    .doOnSuccess(v -> log.info("Published event for order {}", orderId))
                    .doOnError(e -> log.error("Failed to publish event for order {}: {}", orderId, e.getMessage(), e))
                    .then(Mono.just(new Order(orderId, "pending", correlationId)))
                    .doOnSuccess(v -> log.info("Created order object for {}", orderId))
                    .doOnError(e -> log.error("Error in createOrder for order {}: {}", orderId, e.getMessage(), e));
        });

        return transactionalOperator.transactional(orderMono)
                .doOnSuccess(order -> {
                    createOrderTimer.stop(meterRegistry.timer("order.creation.timer", "status", "success"));
                    log.info("Order {} created correlationId {}", orderId, correlationId);
                })
                .doOnError(e -> {
                    createOrderTimer.stop(meterRegistry.timer("order.creation.timer",
                            "status", "failed",
                            "error_type", e.getClass().getSimpleName()));
                    log.error("Transactional error in createOrder for order {}: {}", orderId, e.getMessage(), e);
                })
                .onErrorResume(e -> {
                    log.error("Transactional error in createOrder for order {}: {}", orderId, e.getMessage(), e);
                    OrderFailedEvent failedEvent = new OrderFailedEvent(
                            orderId, correlationId, eventId, "createOrder",
                            String.format("%s: %s", e.getClass().getSimpleName(), e.getMessage()));
                    return publishFailedEvent(failedEvent)
                            .then(Mono.just(new Order(orderId, "failed", correlationId)));
                });
    }

    @Override
    public Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        return publishEvent(event, "failedEvent", EventTopics.ORDER_FAILED.getTopic()).then();
    }

    @Override
    public Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        // Obtenemos o creamos un circuit breaker específico para la publicación de eventos
        CircuitBreaker circuitBreaker = getOrCreateStepCircuitBreaker("event_publishing");

        // Enriquecemos el contexto de diagnóstico
        Map<String, String> diagnosticContext = Map.of(
                "eventId", event.getEventId(),
                "eventType", event.getType().name(),
                "step", step,
                "topic", topic,
                "orderId", event.getOrderId().toString(),
                "correlationId", event.getCorrelationId()
        );

        Timer.Sample publishTimer = Timer.start(meterRegistry);

        return withDiagnosticContext(diagnosticContext, () -> {
            log.info("Publishing {} event {} for step {} to topic {}",
                    event.getType(), event.getEventId(), step, topic);

            return eventPublisher.publishEvent(event, step, topic)
                    .map(EventPublishOutcome::getEvent)
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                    .doOnSuccess(v -> {
                        publishTimer.stop(meterRegistry.timer("event.publish.timer",
                                "step", step,
                                "topic", topic,
                                "event_type", event.getType().name(),
                                "status", "success"));
                        log.info("Published event {} for step {}", event.getEventId(), step);
                    })
                    .doOnError(e -> {
                        publishTimer.stop(meterRegistry.timer("event.publish.timer",
                                "step", step,
                                "topic", topic,
                                "event_type", event.getType().name(),
                                "status", "failed",
                                "error_type", e.getClass().getSimpleName()));
                        log.error("Failed to publish event {} for step {}: {}",
                                event.getEventId(), step, e.getMessage(), e);
                    });
        });
    }

    @Override
    public Mono<Void> createFailedEvent(String reason, String externalReference) {
        Long orderId = idGenerator.generateOrderId();
        String correlationId = idGenerator.generateCorrelationId();
        String eventId = idGenerator.generateEventId();

        // Enriquecemos el contexto de diagnóstico
        Map<String, String> diagnosticContext = Map.of(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "eventId", eventId,
                "reason", reason,
                "externalReference", externalReference
        );

        return withDiagnosticContext(diagnosticContext, () -> {
            log.info("Creating failed event with reason: {}, externalReference: {}", reason, externalReference);

            OrderFailedEvent event = new OrderFailedEvent(orderId, correlationId, eventId, "processOrder", reason);
            return publishFailedEvent(event);
        });
    }

    /**
     * Helper method para enriquecer el contexto de diagnóstico
     */
    private <T> Mono<T> withDiagnosticContext(Map<String, String> context, Supplier<Mono<T>> operation) {
        return Mono.fromCallable(() -> {
                    context.forEach(MDC::put);
                    return true;
                })
                .flatMap(result -> operation.get())
                .doFinally(signal -> MDC.clear());
    }

    /**
     * Obtiene o crea un circuit breaker específico para un paso
     */
    private CircuitBreaker getOrCreateStepCircuitBreaker(String stepName) {
        return stepCircuitBreakers.computeIfAbsent(stepName, name -> {
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(10)
                    .failureRateThreshold(50.0f)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .permittedNumberOfCallsInHalfOpenState(3)
                    .recordExceptions(Exception.class)
                    .build();

            return circuitBreakerRegistry.circuitBreaker(name + "-circuit-breaker", config);
        });
    }

    /**
     * Obtiene o crea un bulkhead específico para un paso
     */
    private Bulkhead getOrCreateStepBulkhead(String stepName) {
        return stepBulkheads.computeIfAbsent(stepName, name -> {
            BulkheadConfig config = BulkheadConfig.custom()
                    .maxConcurrentCalls(20)
                    .maxWaitDuration(Duration.ofMillis(500))
                    .build();

            return bulkheadRegistry.bulkhead(name + "-bulkhead", config);
        });
    }
}