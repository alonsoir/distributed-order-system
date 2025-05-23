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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

import static com.example.order.config.SagaStepConstants.STEP_CREATE_ORDER;

@Slf4j
@Component
@Qualifier("atLeastOnceV2")
public class SagaOrchestratorAtLeastOnceImplV2 extends BaseSagaOrchestratorV2 implements SagaOrchestrator {

    private final InventoryService inventoryService;
    private final CompensationManager compensationManager;

    public SagaOrchestratorAtLeastOnceImplV2(
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            @Qualifier("orderEventPublisher") EventPublisher eventPublisher,
            InventoryService inventoryService,
            CompensationManager compensationManager,
            EventRepository eventRepository) {
        super(transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                eventRepository);
        this.inventoryService = inventoryService;
        this.compensationManager = compensationManager;
    }

    @Override
    public Mono<Order> executeOrderSaga(int quantity, double amount) {
        Long orderId = idGenerator.generateOrderId();
        String eventId = idGenerator.generateEventId();
        String correlationId = idGenerator.generateCorrelationId();
        String externalReference = idGenerator.generateExternalReference();

        // Usar ReactiveUtils para crear el contexto
        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "eventId", eventId,
                "quantity", String.valueOf(quantity),
                "amount", String.valueOf(amount),
                "externalReference", externalReference
        );

        // Crear tag para métricas
        Tag correlationTag = Tag.of("correlation_id", correlationId);

        // Usar ReactiveUtils.withContextAndMetrics
        return ReactiveUtils.withContextAndMetrics(
                        context,
                        () -> {
                            log.info("Starting order saga execution with quantity={}, amount={}", quantity, amount);

                            // CORREGIDO: Flujo simplificado y más robusto
                            return createOrder(orderId, correlationId, eventId, externalReference, quantity)
                                    .flatMap(order -> {
                                        log.info("Order created, proceeding to reserve stock");
                                        return executeStep(createReserveStockStep(inventoryService, orderId, quantity, correlationId, eventId, externalReference));
                                    })
                                    .flatMap(event -> {
                                        log.info("Stock reserved, validating transition to ORDER_COMPLETED");
                                        return completeOrderSafely(orderId, correlationId);
                                    });
                        },
                        meterRegistry,
                        MetricsConstants.METRIC_SAGA_EXECUTION,
                        correlationTag
                )
                .onErrorResume(e -> {
                    log.error("Order saga failed: {}", e.getMessage(), e);
                    return handleSagaError(orderId, correlationId, e);
                });
    }

    /**
     * NUEVO: Método para completar la orden de forma segura, manejando transiciones de estado
     */
    private Mono<Order> completeOrderSafely(Long orderId, String correlationId) {
        return eventRepository.getOrderStatus(orderId)
                .defaultIfEmpty(OrderStatus.ORDER_UNKNOWN)
                .flatMap(currentStatus -> {
                    log.debug("Current order status: {} for order {}", currentStatus, orderId);

                    // Determinar el mejor estado de finalización basado en el estado actual
                    OrderStatus targetStatus = determineBestCompletionStatus(currentStatus);

                    if (stateMachine.isValidTransition(currentStatus, targetStatus)) {
                        log.info("Transitioning order {} from {} to {}", orderId, currentStatus, targetStatus);
                        return updateOrderStatus(orderId, targetStatus, correlationId);
                    } else {
                        log.warn("Cannot transition from {} to {} for order {}", currentStatus, targetStatus, orderId);

                        // Buscar estados alternativos progresivos
                        Set<OrderStatus> validNextStates = stateMachine.getValidNextStates(currentStatus);
                        if (!validNextStates.isEmpty()) {
                            OrderStatus nextStatus = determineNextBestState(validNextStates, targetStatus);
                            log.info("Transitioning to {} instead for order {}", nextStatus, orderId);
                            return updateOrderStatus(orderId, nextStatus, correlationId);
                        } else {
                            log.warn("No valid transitions available from {}, keeping current status", currentStatus);
                            return Mono.just(new Order(orderId, currentStatus, correlationId));
                        }
                    }
                });
    }

    /**
     * NUEVO: Determina el mejor estado de finalización basado en el estado actual
     */
    private OrderStatus determineBestCompletionStatus(OrderStatus currentStatus) {
        // Si ya está en un estado avanzado, mantenerlo
        if (currentStatus == OrderStatus.DELIVERED ||
                currentStatus == OrderStatus.RECEIVED_CONFIRMED) {
            return OrderStatus.ORDER_COMPLETED;
        }

        // Para estados intermedios, buscar progresión natural
        Set<OrderStatus> validNextStates = stateMachine.getValidNextStates(currentStatus);

        if (validNextStates.contains(OrderStatus.ORDER_COMPLETED)) {
            return OrderStatus.ORDER_COMPLETED;
        }

        // Estados progresivos en orden de preferencia
        if (validNextStates.contains(OrderStatus.ORDER_PROCESSING)) {
            return OrderStatus.ORDER_PROCESSING;
        }
        if (validNextStates.contains(OrderStatus.ORDER_PREPARED)) {
            return OrderStatus.ORDER_PREPARED;
        }
        if (validNextStates.contains(OrderStatus.SHIPPING_PENDING)) {
            return OrderStatus.SHIPPING_PENDING;
        }

        // Si no hay buena opción, mantener el estado actual
        return currentStatus;
    }

    /**
     * NUEVO: Maneja errores en la saga de forma centralizada
     */
    private Mono<Order> handleSagaError(Long orderId, String correlationId, Throwable error) {
        return eventRepository.getOrderStatus(orderId)
                .defaultIfEmpty(OrderStatus.ORDER_UNKNOWN)
                .flatMap(currentStatus -> {
                    OrderStatus errorStatus = determineErrorStatus(currentStatus);
                    log.info("Transitioning order {} to error state: {}", orderId, errorStatus);
                    return updateOrderStatus(orderId, errorStatus, correlationId);
                })
                .onErrorResume(updateError -> {
                    log.error("Failed to update order status during error handling: {}", updateError.getMessage());
                    // Retornar orden con estado de error técnico como fallback
                    return Mono.just(new Order(orderId, OrderStatus.TECHNICAL_EXCEPTION, correlationId));
                });
    }

    /**
     * Determina el mejor estado siguiente cuando la transición deseada no es válida
     */
    private OrderStatus determineNextBestState(Set<OrderStatus> validNextStates, OrderStatus desiredState) {
        // Si el estado deseado está en los válidos, lo usamos
        if (validNextStates.contains(desiredState)) {
            return desiredState;
        }

        // Estrategia de priorización: buscar estados que nos acerquen al objetivo
        if (desiredState == OrderStatus.ORDER_COMPLETED) {
            if (validNextStates.contains(OrderStatus.ORDER_PROCESSING)) {
                return OrderStatus.ORDER_PROCESSING;
            }
            if (validNextStates.contains(OrderStatus.ORDER_PREPARED)) {
                return OrderStatus.ORDER_PREPARED;
            }
            if (validNextStates.contains(OrderStatus.SHIPPING_PENDING)) {
                return OrderStatus.SHIPPING_PENDING;
            }
        }

        // Si no hay una buena opción progresiva, usar el primer estado válido
        return validNextStates.iterator().next();
    }

    @Override
    public Mono<OrderEvent> executeStep(SagaStep step) {
        if (step == null) {
            return Mono.error(new IllegalArgumentException("SagaStep cannot be null"));
        }

        // Crear contexto y tags para métricas
        Map<String, String> context = ReactiveUtils.createContext(
                "stepName", step.getName(),
                "orderId", step.getOrderId().toString(),
                "correlationId", step.getCorrelationId(),
                "eventId", step.getEventId(),
                "externalReference", step.getExternalReference()
        );

        Tag stepTag = Tag.of("step", step.getName());

        return ReactiveUtils.withContextAndMetrics(
                        context,
                        () -> {
                            log.info("Preparing to execute step {} for order {} correlationId {}",
                                    step.getName(), step.getOrderId(), step.getCorrelationId());

                            // Verificar idempotencia primero - implementación AT LEAST ONCE
                            return isEventProcessed(step.getEventId())
                                    .flatMap(processed -> {
                                        if (processed) {
                                            log.info("Step {} for order {} already processed, returning success event without execution",
                                                    step.getName(), step.getOrderId());
                                            return Mono.just(step.getSuccessEvent().apply(step.getEventId()));
                                        }

                                        log.info("Executing step {} for order {} (new execution)",
                                                step.getName(), step.getOrderId());

                                        // CORREGIDO: Flujo de ejecución más robusto
                                        return executeStepAction(step);
                                    });
                        },
                        meterRegistry,
                        MetricsConstants.METRIC_SAGA_STEP,
                        stepTag
                )
                .doOnSuccess(event -> {
                    log.info("Step {} completed successfully for order {}", step.getName(), step.getOrderId());
                    meterRegistry.counter(MetricsConstants.COUNTER_SAGA_STEP_SUCCESS, "step", step.getName()).increment();
                })
                .doOnError(e -> {
                    log.error("Step {} failed for order {}: {}", step.getName(), step.getOrderId(), e.getMessage(), e);
                    meterRegistry.counter(MetricsConstants.COUNTER_SAGA_STEP_FAILED, "step", step.getName()).increment();
                })
                .transform(resilienceManager.applyResilience(step.getName()))
                .onErrorResume(e -> handleStepError(step, e, compensationManager));
    }

    /**
     * NUEVO: Ejecuta la acción del paso de forma transaccional
     */
    private Mono<OrderEvent> executeStepAction(SagaStep step) {
        return eventRepository.saveEventHistory(
                        step.getEventId(),
                        step.getCorrelationId(),
                        step.getOrderId(),
                        "SAGA_STEP",
                        step.getName(),
                        "STARTED")
                .then(
                        transactionalOperator.transactional(
                                step.getAction().get()
                                        .then(markEventAsProcessed(step.getEventId()))
                                        .then(eventRepository.saveEventHistory(
                                                step.getEventId(),
                                                step.getCorrelationId(),
                                                step.getOrderId(),
                                                "SAGA_STEP",
                                                step.getName(),
                                                "COMPLETED"))
                                        .then(Mono.defer(() -> {
                                            log.info("Step action completed, publishing success event");
                                            OrderEvent successEvent = step.getSuccessEvent().apply(step.getEventId());

                                            String topic = determineStepTopic(step);
                                            return publishEvent(successEvent, step.getName(), topic);
                                        }))
                        )
                );
    }

    /**
     * NUEVO: Determina el topic apropiado para un paso
     */
    private String determineStepTopic(SagaStep step) {
        String topic = step.getTopic();

        // Validación adicional para pasos específicos
        if ("reserveStock".equals(step.getName())) {
            String expectedTopic = stateMachine.getTopicNameForTransition(
                    OrderStatus.PAYMENT_CONFIRMED, OrderStatus.STOCK_RESERVED);
            if (expectedTopic != null && !expectedTopic.equals(topic)) {
                log.warn("Topic mismatch for step {}: expected {}, got {}",
                        step.getName(), expectedTopic, topic);
            }
        }

        return topic;
    }

    @Override
    public Mono<Order> createOrder(Long orderId, String correlationId, String eventId, String externalReference, int quantity) {
        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "eventId", eventId,
                "externalReference", externalReference,
                "quantity", String.valueOf(quantity)
        );

        Tag correlationTag = Tag.of("correlation_id", correlationId);

        return ReactiveUtils.withContextAndMetrics(
                        context,
                        () -> {
                            log.info("Creating order {} with correlationId {}, eventId {} and externalReference {}",
                                    orderId, correlationId, eventId, externalReference);

                            OrderEvent event = new OrderCreatedEvent(orderId, correlationId, eventId, externalReference, quantity);

                            // Determinar el topic usando la máquina de estados
                            String topic = stateMachine.getTopicNameForTransition(
                                    OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_CREATED);

                            if (topic == null) {
                                topic = EventTopics.getTopicName(OrderStatus.ORDER_CREATED);
                                log.warn("No topic defined for ORDER_UNKNOWN -> ORDER_CREATED transition, using default: {}", topic);
                            }

                            return transactionalOperator.transactional(
                                    insertOrderData(orderId, correlationId, eventId, event)
                                            .then(publishEvent(event, STEP_CREATE_ORDER, topic))
                                            .then(Mono.just(new Order(orderId, OrderStatus.ORDER_PENDING, correlationId)))
                                            .doOnSuccess(v -> log.info("Created order object for {}", orderId))
                            );
                        },
                        meterRegistry,
                        MetricsConstants.METRIC_ORDER_CREATION,
                        correlationTag
                )
                .onErrorResume(e -> {
                    log.error("Error in createOrder for order {}: {}", orderId, e.getMessage(), e);
                    return handleCreateOrderError(orderId, correlationId, eventId, externalReference, e);
                });
    }

    @Override
    public Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        return super.publishEvent(event, step, topic);
    }

    @Override
    public Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        return super.publishFailedEvent(event);
    }

    @Override
    public Mono<Void> createFailedEvent(String reason, String externalReference) {
        Long orderId = idGenerator.generateOrderId();
        String correlationId = idGenerator.generateCorrelationId();
        String eventId = idGenerator.generateEventId();

        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "eventId", eventId,
                "reason", reason,
                "externalReference", externalReference
        );

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.info("Creating failed event with reason: {}, externalReference: {}", reason, externalReference);

            String failureTopic = stateMachine.getTopicNameForTransition(
                    OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_FAILED);

            if (failureTopic == null) {
                failureTopic = EventTopics.getTopicName(OrderStatus.ORDER_FAILED);
                log.warn("No topic defined for ORDER_UNKNOWN -> ORDER_FAILED transition, using default: {}", failureTopic);
            }

            OrderFailedEvent event = new OrderFailedEvent(orderId, correlationId, eventId,
                    SagaStepType.PROCESS_ORDER, reason, externalReference);
            return publishFailedEvent(event);
        });
    }
}