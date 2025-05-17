package com.example.order.service.v2;

import com.example.order.config.SagaConfig;
import com.example.order.domain.Order;
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

                    return createOrder(orderId, correlationId, eventId, externalReference, quantity)
                            .flatMap(order -> {
                                log.info("Order created, proceeding to reserve stock");
                                return executeStep(createReserveStockStep(inventoryService, orderId, quantity, correlationId, eventId, externalReference));
                            })
                            .flatMap(event -> {
                                log.info("Stock reserved, updating order status to completed");
                                return updateOrderStatus(orderId, "completed", correlationId);
                            })
                            .onErrorResume(e -> {
                                log.error("Order saga failed: {}", e.getMessage(), e);
                                return updateOrderStatus(orderId, "failed", correlationId);
                            });
                },
                meterRegistry,
                SagaConfig.METRIC_SAGA_EXECUTION,
                correlationTag
        );
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
                                    // Si ya se procesó, simplemente devolvemos el evento de éxito sin ejecutar la acción
                                    log.info("Step {} for order {} already processed, returning success event without execution",
                                            step.getName(), step.getOrderId());
                                    return Mono.just(step.getSuccessEvent().apply(step.getEventId()));
                                }

                                log.info("Executing step {} for order {} (new execution)",
                                        step.getName(), step.getOrderId());

                                // Registrar el inicio de la ejecución del paso
                                return eventRepository.saveEventHistory(
                                                step.getEventId(),
                                                step.getCorrelationId(),
                                                step.getOrderId(),
                                                "SAGA_STEP",
                                                step.getName(),
                                                "STARTED")
                                        .then(
                                                // Ejecutar la acción del paso y publicar el evento de éxito
                                                transactionalOperator.transactional(
                                                        step.getAction().get()
                                                                // Marcar el evento como procesado en la misma transacción
                                                                .then(markEventAsProcessed(step.getEventId()))
                                                                // Registrar el resultado exitoso
                                                                .then(eventRepository.saveEventHistory(
                                                                        step.getEventId(),
                                                                        step.getCorrelationId(),
                                                                        step.getOrderId(),
                                                                        "SAGA_STEP",
                                                                        step.getName(),
                                                                        "COMPLETED"))
                                                                .then(Mono.defer(() -> {
                                                                    log.info("Step action completed, publishing success event");
                                                                    // Crear y publicar el evento de éxito
                                                                    OrderEvent successEvent = step.getSuccessEvent().apply(step.getEventId());
                                                                    return publishEvent(successEvent, step.getName(), step.getTopic());
                                                                }))
                                                )
                                        );
                            })
                            .doOnSuccess(event -> {
                                log.info("Step {} completed successfully for order {}",
                                        step.getName(), step.getOrderId());
                                meterRegistry.counter(SagaConfig.COUNTER_SAGA_STEP_SUCCESS,
                                        "step", step.getName()).increment();
                            })
                            .doOnError(e -> {
                                log.error("Step {} failed for order {}: {}",
                                        step.getName(), step.getOrderId(), e.getMessage(), e);
                                meterRegistry.counter(SagaConfig.COUNTER_SAGA_STEP_FAILED,
                                        "step", step.getName()).increment();
                            })
                            // Aplicamos resiliencia utilizando el ResilienceManager
                            .transform(resilienceManager.applyResilience(step.getName()))
                            // Manejamos errores con compensación
                            .onErrorResume(e -> handleStepError(step, e, compensationManager));
                },
                meterRegistry,
                SagaConfig.METRIC_SAGA_STEP,
                stepTag
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

                    Mono<Order> orderMono = insertOrderData(orderId, correlationId, eventId, event)
                            .then(publishEvent(event, "createOrder", EventTopics.ORDER_CREATED.getTopic()))
                            .then(Mono.just(new Order(orderId, "pending", correlationId)))
                            .doOnSuccess(v -> log.info("Created order object for {}", orderId))
                            .doOnError(e -> log.error("Error in createOrder for order {}: {}",
                                    orderId, e.getMessage(), e));

                    return transactionalOperator.transactional(orderMono)
                            .onErrorResume(e -> handleCreateOrderError(orderId,
                                    correlationId,
                                    eventId,
                                    externalReference,
                                    e));
                },
                meterRegistry,
                SagaConfig.METRIC_ORDER_CREATION,
                correlationTag
        );
    }

    @Override
    public Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        // Reutiliza el método de la clase base
        return super.publishEvent(event, step, topic);
    }

    @Override
    public Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        // Reutiliza el método de la clase base
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
            log.info("Creating failed event with reason: {}, externalReference: {}",
                    reason, externalReference);

            OrderFailedEvent event = new OrderFailedEvent(orderId,
                    correlationId,
                    eventId, SagaStepType.PROCESS_ORDER,
                    reason,
                    externalReference);
            return publishFailedEvent(event);
        });
    }
}