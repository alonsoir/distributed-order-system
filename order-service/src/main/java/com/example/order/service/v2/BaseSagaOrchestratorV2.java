package com.example.order.service.v2;

import com.example.order.config.MetricsConstants;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.Order;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.model.SagaStep;
import com.example.order.repository.EventRepository;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.*;
import com.example.order.domain.OrderStateMachine;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.Set;

@Slf4j
public abstract class BaseSagaOrchestratorV2 {

    protected final TransactionalOperator transactionalOperator;
    protected final MeterRegistry meterRegistry;
    protected final IdGenerator idGenerator;
    protected final ResilienceManager resilienceManager;
    protected final EventPublisher eventPublisher;
    protected final EventRepository eventRepository;
    protected final OrderStateMachine stateMachine;

    public BaseSagaOrchestratorV2(
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
        this.stateMachine = OrderStateMachine.getInstance(); // Usar el singleton
    }

    /**
     * Publica un evento utilizando el EventPublisher
     */
    protected Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        log.info("Publishing {} event {} for step {} to topic {}",
                event.getClass().getSimpleName(), event.getEventId(), step, topic);

        return eventPublisher.publishEvent(event, step, topic)
                .map(outcome -> {
                    if (outcome.isSuccess()) {
                        log.info("Published event {} for step {}", event.getEventId(), step);
                        return event;
                    } else {
                        log.error("Failed to publish event {} for step {}: {}",
                                event.getEventId(), step, outcome.getError().getMessage());
                        throw new RuntimeException("Failed to publish event: " + outcome.getError().getMessage());
                    }
                });
    }

    /**
     * Publica un evento de fallo
     */
    protected Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        log.info("Publishing failure event for order {} with reason: {}",
                event.getOrderId(), event.getReason());

        // Determinar el topic adecuado para el evento de fallo
        String topic = stateMachine.getTopicNameForTransition(
                OrderStatus.ORDER_CREATED, OrderStatus.ORDER_FAILED);

        if (topic == null) {
            topic = EventTopics.getTopicName(OrderStatus.ORDER_FAILED);
            log.warn("No topic defined for failure transition {} -> {}, using default: {}",
                    OrderStatus.ORDER_CREATED, OrderStatus.ORDER_FAILED, topic);
        }

        return publishEvent(event, "failedEvent", topic)
                .then();
    }

    /**
     * Verifica si un evento ya ha sido procesado
     */
    protected Mono<Boolean> isEventProcessed(String eventId) {
        return eventRepository.isEventProcessed(eventId)
                .doOnNext(processed -> {
                    if (processed) {
                        log.info("Event {} has already been processed", eventId);
                    }
                });
    }

    /**
     * Marca un evento como procesado
     */
    protected Mono<Void> markEventAsProcessed(String eventId) {
        return eventRepository.markEventAsProcessed(eventId);
    }

    /**
     * Inserta los datos de la orden en el repositorio
     */
    protected Mono<Void> insertOrderData(Long orderId, String correlationId, String eventId, OrderEvent event) {
        return eventRepository.saveOrderData(orderId, correlationId, eventId, event)
                .doOnSuccess(v -> log.info("Inserted order data for order {}", orderId));
    }

    /**
     * Actualiza el estado de una orden
     */
    protected Mono<Order> updateOrderStatus(Long orderId, OrderStatus newStatus, String correlationId) {
        return eventRepository.getOrderStatus(orderId)
                .defaultIfEmpty(OrderStatus.ORDER_UNKNOWN)
                .flatMap(currentStatus -> {
                    // Validar la transición usando la máquina de estados
                    if (!stateMachine.isValidTransition(currentStatus, newStatus)) {
                        log.warn("Invalid state transition from {} to {} for order {}",
                                currentStatus, newStatus, orderId);

                        // Buscar un estado alternativo válido
                        Set<OrderStatus> validNextStates = stateMachine.getValidNextStates(currentStatus);
                        if (!validNextStates.isEmpty()) {
                            OrderStatus alternativeStatus = determineAlternativeStatus(validNextStates, newStatus);
                            log.info("Transitioning to {} instead for order {}", alternativeStatus, orderId);
                            return eventRepository.updateOrderStatus(orderId, alternativeStatus, correlationId);
                        } else {
                            // Si no hay estados válidos, usar TECHNICAL_EXCEPTION
                            log.info("No valid transitions available, using TECHNICAL_EXCEPTION for order {}", orderId);
                            return eventRepository.updateOrderStatus(orderId, OrderStatus.TECHNICAL_EXCEPTION, correlationId);
                        }
                    }

                    return eventRepository.updateOrderStatus(orderId, newStatus, correlationId);
                })
                .doOnSuccess(order -> log.info("Updated order {} to status {}", orderId, order.status()));
    }

    /**
     * Determina un estado alternativo cuando la transición deseada no es válida
     */
    private OrderStatus determineAlternativeStatus(Set<OrderStatus> validStates, OrderStatus desiredStatus) {
        // Si el estado deseado está disponible, usarlo
        if (validStates.contains(desiredStatus)) {
            return desiredStatus;
        }

        // Estrategias de fallback basadas en el estado deseado
        if (desiredStatus == OrderStatus.ORDER_COMPLETED) {
            // Para completar, buscar estados progresivos
            if (validStates.contains(OrderStatus.ORDER_PROCESSING)) return OrderStatus.ORDER_PROCESSING;
            if (validStates.contains(OrderStatus.ORDER_PREPARED)) return OrderStatus.ORDER_PREPARED;
            if (validStates.contains(OrderStatus.SHIPPING_PENDING)) return OrderStatus.SHIPPING_PENDING;
        } else if (desiredStatus == OrderStatus.ORDER_FAILED) {
            // Para fallos, buscar estados de error
            if (validStates.contains(OrderStatus.TECHNICAL_EXCEPTION)) return OrderStatus.TECHNICAL_EXCEPTION;
            if (validStates.contains(OrderStatus.WAITING_RETRY)) return OrderStatus.WAITING_RETRY;
        }

        // Si no hay buena alternativa, usar el primer estado válido
        return validStates.iterator().next();
    }

    /**
     * Determina el estado de error apropiado basado en el estado actual
     */
    protected OrderStatus determineErrorStatus(OrderStatus currentStatus) {
        Set<OrderStatus> validNextStates = stateMachine.getValidNextStates(currentStatus);

        // Priorizar estados de error específicos
        if (validNextStates.contains(OrderStatus.ORDER_FAILED)) {
            return OrderStatus.ORDER_FAILED;
        }
        if (validNextStates.contains(OrderStatus.TECHNICAL_EXCEPTION)) {
            return OrderStatus.TECHNICAL_EXCEPTION;
        }
        if (validNextStates.contains(OrderStatus.WAITING_RETRY)) {
            return OrderStatus.WAITING_RETRY;
        }

        // Si no hay estados de error válidos, usar TECHNICAL_EXCEPTION como fallback
        return OrderStatus.TECHNICAL_EXCEPTION;
    }

    /**
     * CORREGIDO: Maneja errores en la ejecución de pasos con mejor manejo de nulos
     */
    protected Mono<OrderEvent> handleStepError(SagaStep step, Throwable error, CompensationManager compensationManager) {
        log.error("Step {} failed for order {}: {}", step.getName(), step.getOrderId(), error.getMessage(), error);

        // Registrar el fallo del paso
        Mono<Void> recordFailure = eventRepository.recordStepFailure(
                step.getEventId(),
                step.getOrderId(),
                step.getName(),
                error.getMessage(),
                step.getCorrelationId(),
                "STEP_EXECUTION_ERROR",
                step.getExternalReference()
        );

        // CORREGIDO: Obtener el estado actual y determinar la acción apropiada
        return eventRepository.getOrderStatus(step.getOrderId())
                .defaultIfEmpty(OrderStatus.ORDER_UNKNOWN)
                .flatMap(currentStatus -> {
                    OrderStatus errorStatus = determineErrorStatus(currentStatus);

                    // Actualizar el estado de la orden
                    Mono<Order> updateStatus = updateOrderStatus(step.getOrderId(), errorStatus, step.getCorrelationId());

                    // Crear evento de fallo
                    OrderFailedEvent failureEvent = new OrderFailedEvent(
                            step.getOrderId(),
                            step.getCorrelationId(),
                            step.getEventId(),
                            step.getStepType(),
                            error.getMessage(),
                            step.getExternalReference()
                    );

                    // Determinar el topic para el evento de fallo
                    String failureTopic = stateMachine.getTopicNameForTransition(currentStatus, errorStatus);
                    if (failureTopic == null) {
                        failureTopic = EventTopics.getTopicName(OrderStatus.TECHNICAL_EXCEPTION);
                        log.warn("No topic defined for failure transition {} -> {}, using default: {}",
                                currentStatus, errorStatus, failureTopic);
                    }

                    // CORREGIDO: Mejor manejo de la cadena de operaciones reactivas
                    return recordFailure
                            .then(updateStatus)
                            .then(publishEvent(failureEvent, "failedEvent", failureTopic))
                            .onErrorResume(publishError -> {
                                log.error("Failed to publish failure event: {}", publishError.getMessage());
                                // Retornar el evento de fallo sin fallar la operación
                                return Mono.just(failureEvent);
                            });
                })
                .cast(OrderEvent.class); // Cast seguro ya que OrderFailedEvent extends OrderEvent
    }

    /**
     * CORREGIDO: Maneja errores en la creación de órdenes
     */
    protected Mono<Order> handleCreateOrderError(Long orderId, String correlationId,
                                                 String eventId, String externalReference, Throwable error) {
        log.error("Error creating order {}: {}", orderId, error.getMessage(), error);

        // Registrar el fallo de la saga
        return eventRepository.recordSagaFailure(
                        orderId,
                        correlationId,
                        error.getMessage(),
                        "ORDER_CREATION_ERROR",
                        externalReference)
                .then(Mono.just(new Order(orderId, OrderStatus.ORDER_FAILED, correlationId)))
                .onErrorResume(recordError -> {
                    log.error("Failed to record saga failure: {}", recordError.getMessage());
                    // Aún así retornar la orden fallida
                    return Mono.just(new Order(orderId, OrderStatus.ORDER_FAILED, correlationId));
                });
    }

    /**
     * NUEVO: Crea un paso para reservar stock
     */
    protected SagaStep createReserveStockStep(InventoryService inventoryService, Long orderId,
                                              int quantity, String correlationId, String eventId, String externalReference) {
        return SagaStep.builder()
                .name("reserveStock")
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .externalReference(externalReference)
                .topic("stock-reserved")
                .action(() -> inventoryService.reserveStock(orderId, quantity))
                .successEvent(evtId ->
                        new com.example.order.events.StockReservedEvent(orderId,
                                                                        correlationId,
                                                                        evtId,
                                                                        externalReference,
                                                                        quantity))
                .stepType(com.example.order.model.SagaStepType.PROCESS_ORDER)
                .build();
    }
}