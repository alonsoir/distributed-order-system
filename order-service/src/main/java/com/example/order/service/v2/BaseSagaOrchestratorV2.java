package com.example.order.service.v2;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.config.SagaConfig;
import com.example.order.domain.Order;
import com.example.order.domain.DeliveryMode;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.OrderStateMachine;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.events.StockReservedEvent;
import com.example.order.model.SagaStep;
import com.example.order.model.SagaStepType;
import com.example.order.repository.EventRepository;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.CompensationManager;
import com.example.order.service.EventPublishOutcome;
import com.example.order.service.EventPublisher;
import com.example.order.service.IdGenerator;
import com.example.order.service.InventoryService;
import com.example.order.utils.ReactiveUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * Implementación base para orquestadores de sagas con funcionalidad común.
 * Esta versión usa EventRepository en lugar de DatabaseClient directamente e integra OrderStateMachine.
 */
public abstract class BaseSagaOrchestratorV2 {
    private static final Logger log = LoggerFactory.getLogger(BaseSagaOrchestratorV2.class);

    protected final TransactionalOperator transactionalOperator;
    protected final MeterRegistry meterRegistry;
    protected final IdGenerator idGenerator;
    protected final ResilienceManager resilienceManager;
    protected final EventPublisher eventPublisher;
    protected final EventRepository eventRepository;
    // Añadimos OrderStateMachine como un componente central
    protected final OrderStateMachine stateMachine;

    protected BaseSagaOrchestratorV2(
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            EventPublisher eventPublisher,
            EventRepository eventRepository) {
        this.transactionalOperator = transactionalOperator;
        this.meterRegistry = meterRegistry;
        this.idGenerator = idGenerator;
        this.resilienceManager = resilienceManager;
        this.eventPublisher = eventPublisher;
        this.eventRepository = eventRepository;
        // Inicializamos el stateMachine
        this.stateMachine = OrderStateMachine.getInstance();
    }

    /**
     * Crea un paso de reserva de stock usando el topic del stateMachine
     */
    protected SagaStep createReserveStockStep(
            InventoryService inventoryService,
            Long orderId,
            int quantity,
            String correlationId,
            String eventId,
            String externalReference) {

        // Determinamos el topic adecuado basado en la transición de estado
        String topic = stateMachine.getTopicNameForTransition(
                OrderStatus.PAYMENT_CONFIRMED, OrderStatus.STOCK_RESERVED);

        // Si no hay un topic específico definido, usamos el valor por defecto
        if (topic == null) {
            topic = EventTopics.STOCK_RESERVED.getTopicName();
            log.warn("No topic defined for transition PAYMENT_CONFIRMED -> STOCK_RESERVED, using default: {}", topic);
        }

        return SagaStep.builder()
                .name("reserveStock")
                .topic(topic)
                .action(() -> inventoryService.reserveStock(orderId, quantity))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity))
                .successEvent(eventSuccessId -> new StockReservedEvent(orderId, correlationId, eventId, externalReference, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .externalReference(externalReference)
                .build();
    }

    /**
     * Inserta datos de una orden en la base de datos
     */
    protected Mono<Void> insertOrderData(Long orderId, String correlationId, String eventId, OrderEvent event) {
        return eventRepository.saveOrderData(orderId, correlationId, eventId, event)
                .doOnSuccess(v -> log.info("Inserted order data for order {}", orderId))
                .doOnError(e -> log.error("Failed to insert order data for order {}: {}",
                        orderId, e.getMessage(), e))
                .transform(resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS));
    }



    /**
     * Maneja un error al crear una orden
     */
    protected Mono<Order> handleCreateOrderError(Long orderId,
                                                 String correlationId,
                                                 String eventId,
                                                 String externalReference,
                                                 Throwable e) {
        log.error("Transactional error in createOrder for order {}: {}", orderId, e.getMessage(), e);
        OrderFailedEvent failedEvent = new OrderFailedEvent(
                orderId,
                correlationId,
                eventId,
                SagaStepType.CREATE_ORDER,
                String.format("%s: %s", e.getClass().getSimpleName(), e.getMessage()),
                externalReference
        );
        return publishFailedEvent(failedEvent)
                .then(Mono.just(new Order(orderId, "failed", correlationId)));
    }


    /**
     * Determina el estado de error adecuado basado en el estado actual
     * @param currentStatus El estado actual de la orden
     * @return El estado de error más apropiado
     */
    protected OrderStatus determineErrorStatus(OrderStatus currentStatus) {
        // Verificamos qué estados de error son transiciones válidas desde el estado actual
        Set<OrderStatus> validNextStates = stateMachine.getValidNextStates(currentStatus);

        // Prioridad de estados de error
        if (validNextStates.contains(OrderStatus.ORDER_FAILED)) {
            return OrderStatus.ORDER_FAILED;
        } else if (validNextStates.contains(OrderStatus.TECHNICAL_EXCEPTION)) {
            return OrderStatus.TECHNICAL_EXCEPTION;
        } else if (validNextStates.contains(OrderStatus.SHIPPING_EXCEPTION)) {
            return OrderStatus.SHIPPING_EXCEPTION;
        } else if (validNextStates.contains(OrderStatus.DELIVERY_EXCEPTION)) {
            return OrderStatus.DELIVERY_EXCEPTION;
        } else if (validNextStates.contains(OrderStatus.PAYMENT_DECLINED)) {
            return OrderStatus.PAYMENT_DECLINED;
        } else if (validNextStates.contains(OrderStatus.STOCK_UNAVAILABLE)) {
            return OrderStatus.STOCK_UNAVAILABLE;
        } else if (validNextStates.contains(OrderStatus.ORDER_CANCELED)) {
            return OrderStatus.ORDER_CANCELED;
        } else if (validNextStates.contains(OrderStatus.MANUAL_REVIEW)) {
            return OrderStatus.MANUAL_REVIEW;
        } else {
            // Si no hay un estado de error válido, usamos MANUAL_REVIEW como último recurso
            log.warn("No valid error state transition from {}, forcing MANUAL_REVIEW", currentStatus);
            return OrderStatus.MANUAL_REVIEW;
        }
    }

    /**
     * Publica un evento en el sistema con registro detallado
     */
    protected Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        Map<String, String> context = ReactiveUtils.createContext(
                "eventId", event.getEventId(),
                "eventType", event.getType().name(),
                "step", step,
                "topic", topic,
                "orderId", event.getOrderId().toString(),
                "correlationId", event.getCorrelationId(),
                "externalReference", event.getExternalReference()
        );

        Tag[] tags = new Tag[] {
                Tag.of("step", step),
                Tag.of("topic", topic),
                Tag.of("event_type", event.getType().name())
        };

        return ReactiveUtils.withContextAndMetrics(
                context,
                () -> {
                    log.info("Publishing {} event {} for step {} to topic {}",
                            event.getType(), event.getEventId(), step, topic);

                    // Registrar historial de eventos - intento
                    return eventRepository.saveEventHistory(
                                    event.getEventId(),
                                    event.getCorrelationId(),
                                    event.getOrderId(),
                                    event.getType().name(),
                                    step,
                                    "ATTEMPT")
                            .then(eventPublisher.publishEvent(event, step, topic))
                            .map(EventPublishOutcome::getEvent)
                            .transform(resilienceManager.applyResilience(CircuitBreakerCategory.EVENT_PUBLISHING))
                            .doOnSuccess(v -> {
                                log.info("Published event {} for step {}", event.getEventId(), step);
                                // Registrar éxito
                                eventRepository.saveEventHistory(
                                                event.getEventId(),
                                                event.getCorrelationId(),
                                                event.getOrderId(),
                                                event.getType().name(),
                                                step,
                                                "SUCCESS")
                                        .subscribe();
                            })
                            .doOnError(ex -> {
                                log.error("Failed to publish event {} for step {}: {}",
                                        event.getEventId(), step, ex.getMessage(), ex);
                                // Registrar fallo
                                eventRepository.saveEventHistory(
                                                event.getEventId(),
                                                event.getCorrelationId(),
                                                event.getOrderId(),
                                                event.getType().name(),
                                                step,
                                                "ERROR: " + ex.getMessage())
                                        .subscribe();
                            });
                },
                meterRegistry,
                SagaConfig.METRIC_EVENT_PUBLISH,
                tags
        );
    }

    /**
     * Actualiza el estado de una orden con validación de transición
     */
    protected Mono<Order> updateOrderStatus(Long orderId, OrderStatus newStatus, String correlationId) {
        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "status", newStatus.getValue()
        );

        return ReactiveUtils.withDiagnosticContext(context, () ->
                // Primero obtenemos el estado actual para validar la transición
                eventRepository.getOrderStatus(orderId)
                        .defaultIfEmpty(OrderStatus.ORDER_UNKNOWN)
                        .flatMap(currentStatus -> {
                            // Validamos la transición usando OrderStateMachine
                            if (!stateMachine.isValidTransition(currentStatus, newStatus)) {
                                log.warn("Invalid state transition from {} to {} for order {}",
                                        currentStatus, newStatus, orderId);

                                // Si la transición no es válida, intentamos encontrar una transición alternativa
                                Set<OrderStatus> validNextStates = stateMachine.getValidNextStates(currentStatus);
                                if (validNextStates.contains(OrderStatus.TECHNICAL_EXCEPTION)) {
                                    log.info("Transitioning to TECHNICAL_EXCEPTION instead for order {}", orderId);
                                    return transactionalOperator.transactional(
                                            eventRepository.updateOrderStatus(orderId, OrderStatus.TECHNICAL_EXCEPTION, correlationId)
                                                    .transform(resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS))
                                    );
                                } else {
                                    // Si no hay alternativa viable, permitimos la transición pero la registramos como anomalía
                                    log.error("Forcing invalid transition from {} to {} for order {}",
                                            currentStatus, newStatus, orderId);
                                    meterRegistry.counter("invalid_state_transitions",
                                            "from", currentStatus.name(),
                                            "to", newStatus.name()).increment();
                                }
                            }

                            // Actualizamos el estado
                            return transactionalOperator.transactional(
                                    eventRepository.updateOrderStatus(orderId, newStatus, correlationId)
                                            .transform(resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS))
                            );
                        })
        );
    }

    /**
     * Maneja un error en un paso de saga con registro detallado y transición adecuada
     */
    protected Mono<OrderEvent> handleStepError(SagaStep step, Throwable e, CompensationManager compensationManager) {
        String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
        String errorType = e.getClass().getSimpleName();

        Map<String, String> context = ReactiveUtils.createContext(
                "stepName", step.getName(),
                "orderId", step.getOrderId().toString(),
                "correlationId", step.getCorrelationId(),
                "eventId", step.getEventId(),
                "errorType", errorType,
                "errorMessage", errorMessage
        );

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.error("Step {} failed for order {}: {}", step.getName(), step.getOrderId(), errorMessage, e);

            // Obtener el estado actual para determinar la transición adecuada para el error
            return eventRepository.getOrderStatus(step.getOrderId())
                    .defaultIfEmpty(OrderStatus.ORDER_UNKNOWN)
                    .flatMap(currentStatus -> {
                        // Decidir el estado de error adecuado basado en el estado actual
                        OrderStatus errorStatus = determineErrorStatus(currentStatus);

                        // Crear el evento de fallo
                        OrderFailedEvent failedEvent = new OrderFailedEvent(
                                step.getOrderId(),
                                step.getCorrelationId(),
                                step.getEventId(),
                                SagaStepType.FAILED_ORDER,
                                String.format("%s: %s", errorType, errorMessage),
                                step.getExternalReference());

                        // Obtener el topic adecuado para la transición de error
                        String errorTopic = stateMachine.getTopicNameForTransition(currentStatus, errorStatus);
                        if (errorTopic == null) {
                            // Si no hay un topic específico para esta transición, usamos el predeterminado
                            errorTopic = EventTopics.ORDER_FAILED.getTopicName();
                            log.warn("No topic defined for error transition {} -> {}, using default: {}",
                                    currentStatus, errorStatus, errorTopic);
                        }

                        // Registrar el fallo y ejecutar compensación
                        return eventRepository.recordStepFailure(
                                        step.getName(),
                                        step.getOrderId(),
                                        step.getCorrelationId(),
                                        step.getEventId(),
                                        errorMessage,
                                        errorType,
                                        "FUNCTIONAL")
                                .then(eventRepository.recordSagaFailure(
                                        step.getOrderId(),
                                        step.getCorrelationId(),
                                        errorMessage,
                                        errorType,
                                        "FUNCTIONAL"))
                                // Actualizar el estado de la orden al estado de error
                                .then(eventRepository.updateOrderStatus(step.getOrderId(), errorStatus, step.getCorrelationId()))
                                // Publicar el evento de fallo
                                .then(publishEvent(failedEvent, "failedEvent", errorTopic))
                                // Registrar inicio de compensación
                                .then(eventRepository.insertCompensationLog(
                                        step.getName(),
                                        step.getOrderId(),
                                        step.getCorrelationId(),
                                        step.getEventId(),
                                        currentStatus))
                                // Ejecutar la compensación
                                .then(compensationManager.executeCompensation(step))
                                // Actualizar estado de la compensación a completado
                                .then(eventRepository.insertCompensationLog(
                                        step.getName(),
                                        step.getOrderId(),
                                        step.getCorrelationId(),
                                        step.getEventId(),
                                        errorStatus))
                                .then(Mono.error(e)); // Propagamos el error original
                    });
        });
    }

    /**
     * Publica un evento de fallo con topic definido en OrderStateMachine
     */
    protected Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        Map<String, String> context = ReactiveUtils.createContext(
                "eventId", event.getEventId(),
                "correlationId", event.getCorrelationId(),
                "orderId", event.getOrderId().toString(),
                "reason", event.getReason(),
                "step", SagaStepType.FAILED_EVENT.name(),
                "externalReference", event.getExternalReference()
        );

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.info("Publishing failure event for order {} with reason: {}",
                    event.getOrderId(), event.getReason());

            // Obtener el estado actual para determinar el topic adecuado
            return eventRepository.getOrderStatus(event.getOrderId())
                    .defaultIfEmpty(OrderStatus.ORDER_UNKNOWN)
                    .flatMap(currentStatus -> {
                        // Determinar el topic adecuado usando OrderStateMachine
                        String failureTopic = stateMachine.getTopicNameForTransition(
                                currentStatus, OrderStatus.ORDER_FAILED);

                        if (failureTopic == null) {
                            // Si no hay un topic específico, usamos el predeterminado
                            failureTopic = EventTopics.ORDER_FAILED.getTopicName();
                            log.warn("No topic defined for failure transition {} -> ORDER_FAILED, using default: {}",
                                    currentStatus, failureTopic);
                        }

                        // Registrar fallo con tipo de error específico
                        return eventRepository.recordSagaFailure(
                                        event.getOrderId(),
                                        event.getCorrelationId(),
                                        event.getReason(),
                                        SagaStepType.FAILED_EVENT.name(),
                                        DeliveryMode.AT_LEAST_ONCE)
                                // Continuar con la publicación usando el topic determinado
                                .then(publishEvent(event, "failedEvent", failureTopic).then());
                    });
        });
    }

    /**
     * Verifica si un evento ya ha sido procesado (idempotencia)
     */
    protected Mono<Boolean> isEventProcessed(String eventId) {
        return eventRepository.isEventProcessed(eventId)
                .doOnSuccess(processed -> {
                    if (processed) {
                        log.info("Event {} has already been processed", eventId);
                    }
                });
    }

    /**
     * Registra un evento como procesado
     */
    protected Mono<Void> markEventAsProcessed(String eventId) {
        return eventRepository.markEventAsProcessed(eventId)
                .doOnSuccess(v -> log.debug("Marked event {} as processed", eventId));
    }
}