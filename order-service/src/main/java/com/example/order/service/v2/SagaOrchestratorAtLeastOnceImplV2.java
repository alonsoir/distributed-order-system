package com.example.order.service.v2;

import com.example.order.config.MetricsConstants;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStateMachine;
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
import com.example.order.service.OrderStateMachineService;
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
            EventRepository eventRepository,
            OrderStateMachineService stateMachineService) { // ✅ Cambiar a OrderStateMachineService
        super(transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                eventRepository,
                stateMachineService); // ✅ Pasar OrderStateMachineService
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

        // ✅ Crear máquina de estados específica para esta orden
        OrderStateMachine orderStateMachine = stateMachineService
                .createForOrder(orderId, OrderStatus.ORDER_UNKNOWN);

        // Usar ReactiveUtils.withContextAndMetrics
        return ReactiveUtils.withContextAndMetrics(
                        context,
                        () -> {
                            log.info("Starting AT LEAST ONCE order saga execution with quantity={}, amount={}", quantity, amount);

                            // CORREGIDO: Flujo simplificado y más robusto
                            return createOrder(orderId, correlationId, eventId, externalReference, quantity)
                                    .flatMap(order -> {
                                        log.info("Order created, proceeding to reserve stock");
                                        return executeStep(createReserveStockStep(inventoryService, orderId, quantity, correlationId, eventId, externalReference));
                                    })
                                    .flatMap(event -> {
                                        log.info("Stock reserved, validating transition to ORDER_COMPLETED");
                                        return completeOrderSafely(orderId, correlationId);
                                    })
                                    .doOnSuccess(order -> {
                                        log.info("AT LEAST ONCE order saga completed successfully for orderId={}", orderId);
                                        meterRegistry.counter("saga.completed",
                                                "status", "success",
                                                "type", "at_least_once").increment();
                                    })
                                    .doOnError(e -> {
                                        log.error("AT LEAST ONCE order saga failed: {}", e.getMessage(), e);
                                        meterRegistry.counter("saga.completed",
                                                "status", "error",
                                                "type", "at_least_once").increment();
                                    })
                                    .doFinally(signal -> {
                                        // ✅ SOLUCIÓN: Solo un cleanup en doFinally
                                        stateMachineService.removeForOrder(orderId);
                                        log.debug("Cleaned up state machine for order {}", orderId);
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
     * MIGRACIÓN: Ejecuta la acción del paso de forma transaccional usando OrderStateMachineService
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

                            // MIGRACIÓN: Determinar el topic usando OrderStateMachineService
                            String topic = stateMachineService.getTopicForTransition(
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

            // MIGRACIÓN: Usar OrderStateMachineService para obtener el topic
            String failureTopic = stateMachineService.getTopicForTransition(
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