package com.example.order.service.v2;

import com.example.order.config.SagaConfig;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderCreatedEvent;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.model.SagaStep;
import com.example.order.model.SagaStepType;
import com.example.order.repository.EventRepository;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.CompensationManager;
import com.example.order.service.EventPublisher;
import com.example.order.service.IdGenerator;
import com.example.order.service.InventoryService;
import com.example.order.service.SagaOrchestrator;
import com.example.order.utils.ReactiveUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;

/**
 * Implementación robusta AT MOST ONCE del orquestador de sagas
 * Versión 2: Usa exclusivamente EventRepository y elimina la dependencia de DatabaseClient
 */
@Slf4j
@Component("sagaOrchestratorImplV2")
@Qualifier("sagaOrchestratorImplV2")
public class SagaOrchestratorAtMostOnceImplV2 extends RobustBaseSagaOrchestratorV2 implements SagaOrchestrator {

    private final InventoryService inventoryService;
    private final CompensationManager compensationManager;

    public SagaOrchestratorAtMostOnceImplV2(
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            @Qualifier("orderEventPublisher") EventPublisher eventPublisher,
            InventoryService inventoryService,
            CompensationManager compensationManager,
            EventRepository eventRepository) {
        super(transactionalOperator, meterRegistry, idGenerator,
                resilienceManager, eventPublisher, eventRepository);
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
        meterRegistry.gauge("saga.in_progress", sagasInProgress, AtomicReference::get);

        // Configurar métricas para monitoreo de salud
        meterRegistry.gauge("saga.health.order_creation_success_rate",
                this, o -> o.getSuccessRate("createOrder"));

        meterRegistry.gauge("saga.health.stock_reservation_success_rate",
                this, o -> o.getSuccessRate("reserveStock"));

        log.info("SagaOrchestratorAtMostOnceImplV2 initialized with robust configuration");
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
        String externalReference = idGenerator.generateExternalReference();

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

                                // Flow principal de saga: Primero crear la orden (fuera de la transacción principal)
                                return createOrder(orderId, correlationId, eventId, externalReference, quantity)
                                        .flatMap(order -> {
                                            // IMPORTANTE: Verificar el estado usando el enum
                                            if (order.status() != OrderStatus.ORDER_PENDING) {
                                                log.warn("Order status is not PENDING: {}", order.status());
                                                return Mono.just(order);  // No continuar si no está en pending
                                            }

                                            // Solo si la orden está en estado PENDING, procedemos con la reserva de stock
                                            log.info("Order created, proceeding to reserve stock");

                                            return transactionalOperator.transactional(
                                                    Mono.defer(() -> {
                                                        // Crear y ejecutar el paso de reserva dentro de un Mono.defer
                                                        SagaStep reserveStockStep = createReserveStockStep(
                                                                inventoryService, orderId, quantity, correlationId, eventId, externalReference);

                                                        return executeStep(reserveStockStep)
                                                                .flatMap(event -> {
                                                                    log.info("Stock reserved successfully, updating order status to completed");
                                                                    // Usar el enum para el estado COMPLETED
                                                                    return updateOrderStatus(orderId, OrderStatus.ORDER_COMPLETED.getValue(), correlationId);
                                                                });
                                                    })
                                            );
                                        })
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
                                            // Usar el enum para el estado FAILED
                                            return updateOrderStatus(orderId, OrderStatus.ORDER_FAILED.getValue(), correlationId)
                                                    .doOnSuccess(order -> {
                                                        // Usar doOnSuccess para asegurar que se registre el error original
                                                        recordSagaFailure(orderId, correlationId, e);
                                                    });
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
        String errorMessage = error.getMessage() != null ? error.getMessage() : "Unknown error";
        String exceptionName = error.getClass().getSimpleName(); // Usar getSimpleName() en lugar de getName()
        return eventRepository.recordSagaFailure(
                orderId, correlationId, errorMessage, exceptionName, errorType.name());
    }

    @Override
    public Mono<Order> createOrder(Long orderId, String correlationId, String eventId, String externalReference, int quantity) {
        // Validación de parámetros
        if (orderId == null || correlationId == null || eventId == null || externalReference == null) {
            return Mono.error(new IllegalArgumentException("orderId, correlationId, eventId and externalReference cannot be null"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "eventId", eventId,
                "operation", "createOrder",
                "externalReference", externalReference
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
                                OrderEvent event = new OrderCreatedEvent(orderId, correlationId, eventId, externalReference, quantity);

                                // Transacción atómica para insertarla en BD
                                return transactionalOperator.transactional(
                                                insertOrderData(orderId, correlationId, eventId, event)
                                                        // Usar el enum para el estado PENDING
                                                        .then(Mono.just(new Order(orderId, OrderStatus.ORDER_PENDING, correlationId)))
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
                                        .onErrorResume(e -> handleCreateOrderError(orderId, correlationId, eventId, externalReference, e));
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
                    orderId,
                    correlationId,
                    eventId,
                    SagaStepType.FAILED_ORDER,
                    reason,
                    externalReference);

            // Usar EventRepository para registrar el evento fallido
            return eventRepository.saveEventHistory(
                            eventId, correlationId, orderId,
                            event.getType().name(), "createFailedEvent", reason)
                    .then(publishFailedEvent(event))
                    .doOnSuccess(v -> meterRegistry.counter("saga.failure_event.created").increment())
                    .doOnError(e -> log.error("Error creating failure event: {}", e.getMessage(), e));
        });
    }
}