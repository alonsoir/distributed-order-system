package com.example.order.service.v2;

import com.example.order.config.MetricsConstants;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;

/**
 * Implementación robusta AT MOST ONCE del orquestador de sagas
 * Versión 2: Usa exclusivamente EventRepository, elimina la dependencia de DatabaseClient
 * y utiliza OrderStateMachine para gestión de estados y topics
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

        log.info("SagaOrchestratorAtMostOnceImplV2 initialized with robust configuration and OrderStateMachine integration");
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
                                            // MIGRACIÓN: Verificar el estado usando OrderStateMachine
                                            if (!stateMachine.isValidTransition(order.status(), OrderStatus.STOCK_RESERVED)) {
                                                log.warn("Cannot transition from {} to STOCK_RESERVED for order {}",
                                                        order.status(), orderId);

                                                // Buscar estados alternativos válidos
                                                Set<OrderStatus> validNextStates = stateMachine.getValidNextStates(order.status());
                                                if (validNextStates.contains(OrderStatus.ORDER_PROCESSING)) {
                                                    log.info("Using alternative transition to ORDER_PROCESSING for order {}", orderId);
                                                    return updateOrderStatus(orderId, OrderStatus.ORDER_PROCESSING, correlationId);
                                                } else if (!validNextStates.isEmpty()) {
                                                    OrderStatus alternativeStatus = validNextStates.iterator().next();
                                                    log.info("Using alternative transition to {} for order {}", alternativeStatus, orderId);
                                                    return updateOrderStatus(orderId, alternativeStatus, correlationId);
                                                }

                                                // Si no hay transiciones válidas, retornamos la orden actual
                                                return Mono.just(order);
                                            }

                                            // Solo si la transición es válida, procedemos con la reserva de stock
                                            log.info("Order created, proceeding to reserve stock");

                                            return transactionalOperator.transactional(
                                                    Mono.defer(() -> {
                                                        // Crear y ejecutar el paso de reserva dentro de un Mono.defer
                                                        SagaStep reserveStockStep = createReserveStockStep(
                                                                inventoryService, orderId, quantity, correlationId, eventId, externalReference);

                                                        return executeStep(reserveStockStep)
                                                                .flatMap(event -> {
                                                                    log.info("Stock reserved successfully, checking transition to ORDER_COMPLETED");

                                                                    // MIGRACIÓN: Usar OrderStateMachine para validar transición a ORDER_COMPLETED
                                                                    return findExistingOrder(orderId)
                                                                            .flatMap(currentOrder -> {
                                                                                if (stateMachine.isValidTransition(currentOrder.status(), OrderStatus.ORDER_COMPLETED)) {
                                                                                    log.info("Valid transition to ORDER_COMPLETED, updating order status");
                                                                                    return updateOrderStatus(orderId, OrderStatus.ORDER_COMPLETED, correlationId);
                                                                                } else {
                                                                                    log.warn("Cannot transition from {} to ORDER_COMPLETED, finding alternative",
                                                                                            currentOrder.status());

                                                                                    // Buscar estado alternativo válido
                                                                                    Set<OrderStatus> validStates = stateMachine.getValidNextStates(currentOrder.status());
                                                                                    OrderStatus targetStatus = findBestCompletionStatus(validStates);

                                                                                    if (targetStatus != null) {
                                                                                        log.info("Using alternative completion status: {}", targetStatus);
                                                                                        return updateOrderStatus(orderId, targetStatus, correlationId);
                                                                                    } else {
                                                                                        log.info("No valid completion status found, keeping current order");
                                                                                        return Mono.just(currentOrder);
                                                                                    }
                                                                                }
                                                                            });
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
                                            // MIGRACIÓN: Usar OrderStateMachine para determinar el estado de error adecuado
                                            return findExistingOrder(orderId)
                                                    .defaultIfEmpty(new Order(orderId, OrderStatus.ORDER_UNKNOWN.getValue(), correlationId))
                                                    .flatMap(currentOrder -> {
                                                        OrderStatus errorStatus = determineErrorStatus(currentOrder.status(), e);
                                                        return updateOrderStatus(orderId, errorStatus, correlationId)
                                                                .doOnSuccess(order -> recordSagaFailure(orderId, correlationId, e).subscribe());
                                                    });
                                        });
                            });
                },
                meterRegistry,
                MetricsConstants.METRIC_SAGA_EXECUTION,
                correlationTag
        );
    }

    /**
     * MIGRACIÓN: Busca el mejor estado de finalización basado en los estados válidos disponibles
     */
    private OrderStatus findBestCompletionStatus(Set<OrderStatus> validStates) {
        // Priorizar estados que representen completamiento o progreso hacia completamiento
        if (validStates.contains(OrderStatus.ORDER_COMPLETED)) {
            return OrderStatus.ORDER_COMPLETED;
        }
        if (validStates.contains(OrderStatus.DELIVERED)) {
            return OrderStatus.DELIVERED;
        }
        if (validStates.contains(OrderStatus.PENDING_CONFIRMATION)) {
            return OrderStatus.PENDING_CONFIRMATION;
        }
        if (validStates.contains(OrderStatus.DELIVERED_TO_COURIER)) {
            return OrderStatus.DELIVERED_TO_COURIER;
        }
        if (validStates.contains(OrderStatus.ORDER_PREPARED)) {
            return OrderStatus.ORDER_PREPARED;
        }

        return null; // No hay un estado de completamiento válido
    }

    /**
     * MIGRACIÓN: Determina el estado de error más apropiado basado en el estado actual y el tipo de error
     */
    private OrderStatus determineErrorStatus(OrderStatus currentStatus, Throwable error) {
        Set<OrderStatus> validNextStates = stateMachine.getValidNextStates(currentStatus);
        ErrorType errorType = classifyError(error);

        // Estrategia de selección basada en el tipo de error y estados válidos
        switch (errorType) {
            case TRANSIENT:
                if (validNextStates.contains(OrderStatus.TECHNICAL_EXCEPTION)) {
                    return OrderStatus.TECHNICAL_EXCEPTION;
                }
                if (validNextStates.contains(OrderStatus.WAITING_RETRY)) {
                    return OrderStatus.WAITING_RETRY;
                }
                break;

            case VALIDATION:
                if (validNextStates.contains(OrderStatus.ORDER_FAILED)) {
                    return OrderStatus.ORDER_FAILED;
                }
                if (validNextStates.contains(OrderStatus.MANUAL_REVIEW)) {
                    return OrderStatus.MANUAL_REVIEW;
                }
                break;

            case RESOURCE:
            case COMMUNICATION:
                if (validNextStates.contains(OrderStatus.ORDER_FAILED)) {
                    return OrderStatus.ORDER_FAILED;
                }
                if (validNextStates.contains(OrderStatus.TECHNICAL_EXCEPTION)) {
                    return OrderStatus.TECHNICAL_EXCEPTION;
                }
                break;

            default:
                if (validNextStates.contains(OrderStatus.ORDER_FAILED)) {
                    return OrderStatus.ORDER_FAILED;
                }
                break;
        }

        // Fallback: usar el primer estado de error disponible
        if (validNextStates.contains(OrderStatus.MANUAL_REVIEW)) {
            return OrderStatus.MANUAL_REVIEW;
        }

        // Último recurso
        return OrderStatus.ORDER_FAILED;
    }

    /**
     * Registra un fallo completo de saga para análisis posterior
     */
    private Mono<Void> recordSagaFailure(Long orderId, String correlationId, Throwable error) {
        ErrorType errorType = classifyError(error);
        String errorMessage = error.getMessage() != null ? error.getMessage() : "Unknown error";
        String exceptionName = error.getClass().getSimpleName();
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

                                // MIGRACIÓN: Usar OrderStateMachine para obtener el topic adecuado - DECLARAR COMO FINAL
                                final String topic = Optional.ofNullable(
                                                stateMachine.getTopicNameForTransition(
                                                        OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_CREATED))
                                        .orElseGet(() -> {

                                            log.warn("No topic defined for ORDER_UNKNOWN -> ORDER_CREATED transition, using default: {}",
                                                    EventTopics.getTopicName(OrderStatus.ORDER_CREATED));
                                            return EventTopics.getTopicName(OrderStatus.ORDER_CREATED);
                                        });

                                log.debug("Using topic for ORDER_UNKNOWN -> ORDER_CREATED: {}", topic);

                                // Transacción atómica para insertarla en BD
                                return transactionalOperator.transactional(
                                                insertOrderData(orderId, correlationId, eventId, event)
                                                        // MIGRACIÓN: Usar OrderStatus enum en lugar de string hardcodeado
                                                        .then(Mono.just(new Order(orderId, OrderStatus.ORDER_PENDING, correlationId)))
                                                        .doOnSuccess(v -> log.info("Created order object for {}", orderId))
                                        )
                                        // Publicar evento después de la transacción usando el topic determinado por OrderStateMachine
                                        .flatMap(order -> publishEvent(event, "createOrder", topic)
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
                MetricsConstants.METRIC_ORDER_CREATION,
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

                    // MIGRACIÓN: Validar que la transición del paso sea consistente con OrderStateMachine
                    if (step.getName().equals("reserveStock")) {
                        String expectedTopic = stateMachine.getTopicNameForTransition(
                                OrderStatus.ORDER_PENDING, OrderStatus.STOCK_RESERVED);
                        if (expectedTopic != null && !expectedTopic.equals(step.getTopic())) {
                            log.warn("Topic mismatch for step {}: expected {}, got {}",
                                    step.getName(), expectedTopic, step.getTopic());
                        }
                    }

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
                                        meterRegistry.counter(MetricsConstants.COUNTER_SAGA_STEP_SUCCESS,
                                                "step", step.getName()).increment();
                                        stepTimer.stop(meterRegistry.timer("saga.step.execution.time",
                                                "step", step.getName(),
                                                "result", "success"));
                                    })
                                    .doOnError(e -> {
                                        log.error("Step {} failed for order {}: {}",
                                                step.getName(), step.getOrderId(), e.getMessage(), e);
                                        meterRegistry.counter(MetricsConstants.COUNTER_SAGA_STEP_FAILED,
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
                MetricsConstants.METRIC_SAGA_STEP,
                stepTag
        );
    }

    @Override
    public Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        if (event == null) {
            return Mono.error(new IllegalArgumentException("Failed event cannot be null"));
        }

        // MIGRACIÓN: Usar la implementación de la clase base que ahora usa OrderStateMachine
        return publishFailedEvent(event)
                .doOnSuccess(v -> log.info("Published failure event for order {}", event.getOrderId()))
                .doOnError(e -> log.error("Failed to publish failure event: {}", e.getMessage(), e))
                .onErrorResume(e -> {
                    // Crítico: no pudimos publicar evento de fallo
                    meterRegistry.counter("saga.critical.publish_failed_event.error").increment();
                    return Mono.empty(); // No propagamos el error para no bloquear compensación
                });
    }

    @Override
    public Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        // Usa la implementación de la clase base que ya está migrada a OrderStateMachine
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

            // MIGRACIÓN: Usar OrderStateMachine para obtener el topic adecuado
            String failureTopic = stateMachine.getTopicNameForTransition(
                    OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_FAILED);

            if (failureTopic == null) {
                failureTopic = EventTopics.getTopicName(OrderStatus.ORDER_FAILED);
                log.warn("No topic defined for ORDER_UNKNOWN -> ORDER_FAILED transition, using default: {}",
                        failureTopic);
            } else {
                log.debug("Using topic from state machine for ORDER_UNKNOWN -> ORDER_FAILED: {}", failureTopic);
            }

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
                    .then(publishEvent(event, "failedEvent", failureTopic).then())
                    .doOnSuccess(v -> meterRegistry.counter("saga.failure_event.created").increment())
                    .doOnError(e -> log.error("Error creating failure event: {}", e.getMessage(), e));
        });
    }
}