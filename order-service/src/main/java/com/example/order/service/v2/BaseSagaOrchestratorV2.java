package com.example.order.service.v2;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.config.SagaConfig;
import com.example.order.domain.Order;
import com.example.order.domain.DeliveryMode;
import com.example.order.domain.OrderStatus;
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

/**
 * Implementación base para orquestadores de sagas con funcionalidad común.
 * Esta versión usa EventRepository en lugar de DatabaseClient directamente.
 */
public abstract class BaseSagaOrchestratorV2 {
    private static final Logger log = LoggerFactory.getLogger(BaseSagaOrchestratorV2.class);

    protected final TransactionalOperator transactionalOperator;
    protected final MeterRegistry meterRegistry;
    protected final IdGenerator idGenerator;
    protected final ResilienceManager resilienceManager;
    protected final EventPublisher eventPublisher;
    protected final EventRepository eventRepository;

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
    }

    /**
     * Crea un paso de reserva de stock
     */
    protected SagaStep createReserveStockStep(
            InventoryService inventoryService,
            Long orderId,
            int quantity,
            String correlationId,
            String eventId,
            String externalReference) {
        return SagaStep.builder()
                .name("reserveStock")
                .topic(EventTopics.STOCK_RESERVED.getTopic())
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
     * Actualiza el estado de una orden con registro en historial
     */
    protected Mono<Order> updateOrderStatus(Long orderId, OrderStatus status, String correlationId) {
        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "status", status.getValue()
        );

        return ReactiveUtils.withDiagnosticContext(context, () ->
                // Actualización atómica dentro de una transacción
                transactionalOperator.transactional(
                        eventRepository.updateOrderStatus(orderId, status, correlationId)
                                .transform(resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS))
                ));
    }

    /**
     * Maneja un error en un paso de saga con registro detallado
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

            // Crear el evento de fallo
            OrderFailedEvent failedEvent = new OrderFailedEvent(
                    step.getOrderId(),
                    step.getCorrelationId(),
                    step.getEventId(),
                    SagaStepType.FAILED_ORDER,
                    String.format("%s: %s", errorType, errorMessage),
                    step.getExternalReference());

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
                    // Publicar el evento de fallo
                    .then(publishFailedEvent(failedEvent))
                    // Registrar inicio de compensación
                    .then(eventRepository.insertCompensationLog(
                            step.getName(),
                            step.getOrderId(),
                            step.getCorrelationId(),
                            step.getEventId(),OrderStatus.ORDER_CREATED))
                    // Ejecutar la compensación
                    .then(compensationManager.executeCompensation(step))
                    // Actualizar estado de la compensación a completado
                    .then(eventRepository.insertCompensationLog(
                            step.getName(),
                            step.getOrderId(),
                            step.getCorrelationId(),
                            step.getEventId(),
                            OrderStatus.ORDER_CREATED))

                    .then(Mono.error(e)); // Propagamos el error original
        });
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
     * Publica un evento de fallo con registro en tablas especializadas
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

            // Registrar fallo con tipo de error específico
            return eventRepository.recordSagaFailure(
                            event.getOrderId(),
                            event.getCorrelationId(),
                            event.getReason(),
                            SagaStepType.FAILED_EVENT.name(),
                            DeliveryMode.AT_LEAST_ONCE)
                    // Continuar con la publicación normal
                    .then(publishEvent(event, "failedEvent", EventTopics.ORDER_FAILED.getTopic()).then());
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