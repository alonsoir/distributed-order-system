package com.example.order.service;

import com.example.order.config.SagaConfig;
import com.example.order.domain.Order;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderCreatedEvent;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.model.SagaStep;
import com.example.order.resilience.ResilienceManager;
import com.example.order.utils.ReactiveUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.PostConstruct;

/**
 * Implementación robusta del orquestador de sagas
 */
@Slf4j
@Component("sagaOrchestratorImpl2")
@Qualifier("sagaOrchestratorImpl2")
public class SagaOrchestratorImpl2 extends RobustBaseSagaOrchestrator implements SagaOrchestrator {

    private final InventoryService inventoryService;
    private final CompensationManager compensationManager;

    public SagaOrchestratorImpl2(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            @Qualifier("orderEventPublisher") EventPublisher eventPublisher,
            InventoryService inventoryService,
            CompensationManager compensationManager) {
        super(databaseClient, transactionalOperator, meterRegistry, idGenerator, resilienceManager, eventPublisher);
        this.inventoryService = inventoryService;
        this.compensationManager = compensationManager;
    }

    /**
     * Inicializar métricas y sondas de estado
     */
    @PostConstruct
    public void initialize() {
        // Contador para sagas iniciadas
        meterRegistry.counter("saga.started");

        // Contador para sagas completadas
        meterRegistry.counter("saga.completed");

        // Gauge para sagas en progreso
        AtomicReference<Double> sagasInProgress = new AtomicReference<>(0.0);
        meterRegistry.gauge("saga.in_progress", sagasInProgress);

        // Configurar métricas para monitoreo de salud
        meterRegistry.gauge("saga.health.order_creation_success_rate",
                () -> getSuccessRate("createOrder"));

        meterRegistry.gauge("saga.health.stock_reservation_success_rate",
                () -> getSuccessRate("reserveStock"));

        log.info("SagaOrchestratorImpl2 initialized with robust configuration");
    }

    /**
     * Helper para cálculo de tasa de éxito (placeholder - implementación real consultaría BD)
     */
    private double getSuccessRate(String stepName) {
        // En una implementación real, esto consultaría datos de BD o caché
        // para calcular tasas de éxito por paso de saga en último intervalo
        return 0.95; // Valor de ejemplo
    }

    @Override
    public Mono<Order> executeOrderSaga(int quantity, double amount) {
        // Validación de parámetros
        if (quantity <= 0) {
            return Mono.error(new IllegalArgumentException("Quantity must be positive"));
        }

        if (amount <= 0) {
            return Mono.error(new IllegalArgumentException("Amount must be positive"));
        }

        Long orderId = idGenerator.generateOrderId();
        String eventId = idGenerator.generateEventId();
        String correlationId = idGenerator.generateCorrelationId();

        // Validar IDs generados
        if (orderId == null || eventId == null || correlationId == null) {
            log.error("Failed to generate valid IDs for saga");
            return Mono.error(new IllegalStateException("Failed to generate required IDs"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "eventId", eventId,
                "quantity", String.valueOf(quantity),
                "amount", String.valueOf(amount),
                "operation", "executeOrderSaga"
        );

        Tag correlationTag = Tag.of("correlation_id", correlationId);
        Timer.Sample sagaTimer = Timer.start(meterRegistry);

        return ReactiveUtils.withContextAndMetrics(
                context,
                () -> {
                    log.info("Starting order saga execution with quantity={}, amount={}, orderId={}",
                            quantity, amount, orderId);

                    meterRegistry.counter("saga.started").increment();

                    // Verificar si el evento ya ha sido procesado para garantizar idempotencia
                    return isEventAlreadyProcessed(eventId)
                            .flatMap(processed -> {
                                if (processed) {
                                    log.info("Event {} already processed, retrieving existing order", eventId);
                                    return findExistingOrder(orderId);
                                }

                                // Flow principal de saga
                                return transactionalOperator.transactional(
                                                createOrder(orderId, correlationId, eventId)
                                                        .flatMap(order -> {
                                                            if (!"pending".equals(order.getStatus())) {
                                                                log.warn("Order status is not 'pending': {}", order.getStatus());
                                                                return Mono.just(order);  // No continuar si no está en pending
                                                            }

                                                            log.info("Order created, proceeding to reserve stock");
                                                            SagaStep reserveStockStep = createReserveStockStep(
                                                                    inventoryService, orderId, quantity, correlationId, eventId);

                                                            return executeStep(reserveStockStep)
                                                                    .flatMap(event -> {
                                                                        log.info("Stock reserved successfully, updating order status to completed");
                                                                        return updateOrderStatus(orderId, "completed", correlationId);
                                                                    });
                                                        }))
                                        .timeout(GLOBAL_SAGA_TIMEOUT)
                                        .doOnSuccess(order -> {
                                            log.info("Order saga completed successfully for orderId={}", orderId);
                                            meterRegistry.counter("saga.completed",
                                                    "status", "success").increment();
                                            sagaTimer.stop(meterRegistry.timer("saga.execution.time",
                                                    "result", "success"));
                                        })
                                        .doOnError(e -> {
                                            ErrorType errorType = classifyError(e);
                                            log.error("Order saga failed: {} [Type: {}]",
                                                    e.getMessage(), errorType, e);
                                            meterRegistry.counter("saga.completed",
                                                    "status", "error",
                                                    "error_type", errorType.name()).increment();
                                            sagaTimer.stop(meterRegistry.timer("saga.execution.time",
                                                    "result", "error"));
                                        })
                                        .onErrorResume(e -> {
                                            // Actualizar estado a fallido y registrar error
                                            return updateOrderStatus(orderId, "failed", correlationId)
                                                    .doOnSuccess(v -> recordSagaFailure(orderId, correlationId, e));
                                        });
                            });
                },
                meterRegistry,
                SagaConfig.METRIC_SAGA_EXECUTION,
                correlationTag
        );
    }

    /**
     * Registra un fallo completo de saga para análisis posterior
     */
    private Mono<Void> recordSagaFailure(Long orderId, String correlationId, Throwable error) {
        ErrorType errorType = classifyError(error);

        return databaseClient.sql(
                        "INSERT INTO saga_failures (order_id, correlation_id, error_message, error_type, " +
                                "error_category, timestamp) VALUES (:orderId, :correlationId, :errorMessage, " +
                                ":errorType, :errorCategory, CURRENT_TIMESTAMP)")
                .bind("orderId", orderId)
                .bind("correlationId", correlationId)
                .bind("errorMessage", error.getMessage())
                .bind("errorType", error.getClass().getName())
                .bind("errorCategory", errorType.name())
                .then()
                .onErrorResume(e -> {
                    log.error("Failed to record saga failure: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Order> createOrder(Long orderId, String correlationId, String eventId) {
        // Validación de parámetros
        if (orderId == null || correlationId == null || eventId == null) {
            return Mono.error(new IllegalArgumentException("orderId, correlationId, and eventId cannot be null"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "eventId", eventId,
                "operation", "createOrder"
        );

        Tag correlationTag = Tag.of("correlation_id", correlationId);
        Timer.Sample timer = Timer.start(meterRegistry);

        return ReactiveUtils.withContextAndMetrics(
                context,
                () -> {
                    log.info("Creating order {} with correlationId {} and eventId {}",
                            orderId, correlationId, eventId);

                    // Verificar idempotencia
                    return isEventAlreadyProcessed(eventId)
                            .flatMap(processed -> {
                                if (processed) {
                                    log.info("Event {} already processed, retrieving existing order", eventId);
                                    return findExistingOrder(orderId);
                                }

                                OrderEvent event = new OrderCreatedEvent(orderId, correlationId, eventId, "pending");

                                // Transacción atómica para insertarla en BD
                                return transactionalOperator.transactional(
                                                insertOrderData(orderId, correlationId, eventId, event)
                                                        .then(Mono.just(new Order(orderId, "pending", correlationId)))
                                                        .doOnSuccess(v -> log.info("Created order object for {}", orderId))
                                        )
                                        // Publicar evento después de la transacción
                                        .flatMap(order -> publishEvent(event, "createOrder", EventTopics.ORDER_CREATED.getTopic())
                                                .thenReturn(order))
                                        .doOnSuccess(v -> {
                                            log.info("Order creation completed for {}", orderId);
                                            timer.stop(meterRegistry.timer("saga.order.creation.time",
                                                    "result", "success"));
                                        })
                                        .doOnError(e -> {
                                            log.error("Error in createOrder for order {}: {}",
                                                    orderId, e.getMessage(), e);
                                            timer.stop(meterRegistry.timer("saga.order.creation.time",
                                                    "result", "error"));
                                        })
                                        .onErrorResume(e -> handleCreateOrderError(orderId, correlationId, eventId, e));
                            });
                },
                meterRegistry,
                SagaConfig.METRIC_ORDER_CREATION,
                correlationTag
        );
    }

    @Override
    public Mono<OrderEvent> executeStep(SagaStep step) {
        // Validación robusta del paso
        if (step == null) {
            return Mono.error(new IllegalArgumentException("SagaStep cannot be null"));
        }

        if (step.getName() == null || step.getAction() == null ||
                step.getTopic() == null || step.getOrderId() == null ||
                step.getCorrelationId() == null || step.getEventId() == null ||
                step.getSuccessEvent() == null) {
            return Mono.error(new IllegalArgumentException("SagaStep has missing required fields"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "stepName", step.getName(),
                "orderId", step.getOrderId().toString(),
                "correlationId", step.getCorrelationId(),
                "eventId", step.getEventId(),
                "operation", "executeStep"
        );

        Tag stepTag = Tag.of("step", step.getName());
        Timer.Sample stepTimer = Timer.start(meterRegistry);

        return ReactiveUtils.withContextAndMetrics(
                context,
                () -> {
                    log.info("Executing step {} for order {} correlationId {}",
                            step.getName(), step.getOrderId(), step.getCorrelationId());

                    meterRegistry.counter("saga.step.started",
                            "step", step.getName()).increment();

                    // Ejecutar la acción del paso dentro de una transacción
                    Mono<OrderEvent> stepMono = transactionalOperator.transactional(
                            step.getAction().get()
                                    .timeout(SAGA_STEP_TIMEOUT)
                                    .then(Mono.defer(() -> {
                                        log.info("Step action completed, publishing success event");
                                        OrderEvent successEvent = step.getSuccessEvent().apply(step.getEventId());
                                        return publishEvent(successEvent, step.getName(), step.getTopic());
                                    }))
                                    .doOnSuccess(event -> {
                                        log.info("Step {} completed successfully for order {}",
                                                step.getName(), step.getOrderId());
                                        meterRegistry.counter(SagaConfig.COUNTER_SAGA_STEP_SUCCESS,
                                                "step", step.getName()).increment();
                                        stepTimer.stop(meterRegistry.timer("saga.step.execution.time",
                                                "step", step.getName(),
                                                "result", "success"));
                                    })
                                    .doOnError(e -> {
                                        log.error("Step {} failed for order {}: {}",
                                                step.getName(), step.getOrderId(), e.getMessage(), e);
                                        meterRegistry.counter(SagaConfig.COUNTER_SAGA_STEP_FAILED,
                                                "step", step.getName()).increment();
                                        stepTimer.stop(meterRegistry.timer("saga.step.execution.time",
                                                "step", step.getName(),
                                                "result", "error"));
                                    })
                    );

                    // Aplicar resiliencia y manejo de errores
                    return stepMono
                            .transform(resilienceManager.applyResilience(step.getName()))
                            .onErrorResume(e -> handleStepError(step, e, compensationManager));
                },
                meterRegistry,
                SagaConfig.METRIC_SAGA_STEP,
                stepTag
        );
    }

    @Override
    public Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        if (event == null) {
            return Mono.error(new IllegalArgumentException("Failed event cannot be null"));
        }

        return publishEvent(event, "failedEvent", EventTopics.ORDER_FAILED.getTopic())
                .doOnSuccess(v -> log.info("Published failure event for order {}", event.getOrderId()))
                .doOnError(e -> log.error("Failed to publish failure event: {}", e.getMessage(), e))
                .onErrorResume(e -> {
                    // Crítico: no pudimos publicar evento de fallo
                    meterRegistry.counter("saga.critical.publish_failed_event.error").increment();
                    return Mono.empty(); // No propagamos el error para no bloquear compensación
                })
                .then();
    }

    @Override
    public Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        // Usa la implementación de la clase base
        return super.publishEvent(event, step, topic);
    }

    @Override
    public Mono<Void> createFailedEvent(String reason, String externalReference) {
        if (reason == null) {
            return Mono.error(new IllegalArgumentException("Failure reason cannot be null"));
        }

        Long orderId = idGenerator.generateOrderId();
        String correlationId = idGenerator.generateCorrelationId();
        String eventId = idGenerator.generateEventId();

        // Validar IDs generados
        if (orderId == null || correlationId == null || eventId == null) {
            log.error("Failed to generate valid IDs for failed event");
            return Mono.error(new IllegalStateException("Failed to generate required IDs"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "eventId", eventId,
                "reason", reason,
                "externalReference", externalReference != null ? externalReference : "none",
                "operation", "createFailedEvent"
        );

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.info("Creating failed event with reason: {}, externalReference: {}",
                    reason, externalReference);

            OrderFailedEvent event = new OrderFailedEvent(
                    orderId, correlationId, eventId, "processOrder", reason);

            if (externalReference != null && !externalReference.isEmpty()) {
                event.setExternalReference(externalReference);
            }

            // Registrar el evento fallido en base de datos para trazabilidad
            return databaseClient.sql(
                            "INSERT INTO failed_events (order_id, correlation_id, event_id, reason, external_reference, timestamp) " +
                                    "VALUES (:orderId, :correlationId, :eventId, :reason, :externalReference, CURRENT_TIMESTAMP)")
                    .bind("orderId", orderId)
                    .bind("correlationId", correlationId)
                    .bind("eventId", eventId)
                    .bind("reason", reason)
                    .bind("externalReference", externalReference != null ? externalReference : "")
                    .then()
                    .onErrorResume(e -> {
                        log.error("Failed to record failure event: {}", e.getMessage(), e);
                        return Mono.empty(); // Continuar para al menos intentar publicar
                    })
                    .then(publishFailedEvent(event))
                    .doOnSuccess(v -> meterRegistry.counter("saga.failure_event.created").increment())
                    .doOnError(e -> log.error("Error creating failure event: {}", e.getMessage(), e));
        });
    }
}