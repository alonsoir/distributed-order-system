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

    // MIGRACIÓN: Cambiar de OrderStateMachine a OrderStateMachineService
    protected final OrderStateMachineService stateMachineService;

    public BaseSagaOrchestratorV2(
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            EventPublisher eventPublisher,
            EventRepository eventRepository,
            OrderStateMachineService stateMachineService) { // ✅ Cambio aquí
        this.transactionalOperator = transactionalOperator;
        this.meterRegistry = meterRegistry;
        this.idGenerator = idGenerator;
        this.resilienceManager = resilienceManager;
        this.eventPublisher = eventPublisher;
        this.eventRepository = eventRepository;
        this.stateMachineService = stateMachineService; // ✅ Usar servicio
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
     * Publica un evento de fallo usando OrderStateMachineService
     */
    protected Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        log.info("Publishing failure event for order {} with reason: {}",
                event.getOrderId(), event.getReason());

        // MIGRACIÓN: Usar OrderStateMachineService para obtener el estado actual
        return eventRepository.getOrderStatus(event.getOrderId())
                .defaultIfEmpty(OrderStatus.ORDER_UNKNOWN)
                .flatMap(currentStatus -> {
                    // Determinar el topic adecuado usando OrderStateMachineService
                    String topic = stateMachineService.getTopicForTransition(
                            currentStatus, OrderStatus.ORDER_FAILED);

                    if (topic == null) {
                        topic = EventTopics.getTopicName(OrderStatus.ORDER_FAILED);
                        log.warn("No topic defined for failure transition {} -> ORDER_FAILED, using default: {}",
                                currentStatus, topic);
                    } else {
                        log.debug("Using topic from state machine for {} -> ORDER_FAILED: {}",
                                currentStatus, topic);
                    }

                    return publishEvent(event, "failedEvent", topic);
                })
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
     * Actualiza el estado de una orden usando OrderStateMachineService
     */
    protected Mono<Order> updateOrderStatus(Long orderId, OrderStatus newStatus, String correlationId) {
        return eventRepository.getOrderStatus(orderId)
                .defaultIfEmpty(OrderStatus.ORDER_UNKNOWN)
                .flatMap(currentStatus -> {
                    // MIGRACIÓN: Validar la transición usando OrderStateMachineService
                    if (!stateMachineService.isValidTransition(currentStatus, newStatus)) {
                        log.warn("Invalid state transition from {} to {} for order {}",
                                currentStatus, newStatus, orderId);

                        // Buscar un estado alternativo válido
                        Set<OrderStatus> validNextStates = stateMachineService.getValidNextStates(currentStatus);
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
    protected OrderStatus determineAlternativeStatus(Set<OrderStatus> validStates, OrderStatus desiredStatus) {
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
     * Determina el estado de error apropiado basado en el estado actual usando OrderStateMachineService
     */
    protected OrderStatus determineErrorStatus(OrderStatus currentStatus) {
        Set<OrderStatus> validNextStates = stateMachineService.getValidNextStates(currentStatus);

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
     * MIGRACIÓN: Determina el mejor estado de finalización basado en el estado actual
     */
    protected OrderStatus determineBestCompletionStatus(OrderStatus currentStatus) {
        // Si ya está en un estado avanzado, mantenerlo
        if (currentStatus == OrderStatus.DELIVERED ||
                currentStatus == OrderStatus.RECEIVED_CONFIRMED) {
            return OrderStatus.ORDER_COMPLETED;
        }

        // Para estados intermedios, buscar progresión natural
        Set<OrderStatus> validNextStates = stateMachineService.getValidNextStates(currentStatus);

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
     * MIGRACIÓN: Determina el mejor estado siguiente cuando la transición deseada no es válida
     */
    protected OrderStatus determineNextBestState(Set<OrderStatus> validNextStates, OrderStatus desiredState) {
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

    /**
     * MIGRACIÓN: Método para completar la orden de forma segura, manejando transiciones de estado
     */
    protected Mono<Order> completeOrderSafely(Long orderId, String correlationId) {
        return eventRepository.getOrderStatus(orderId)
                .defaultIfEmpty(OrderStatus.ORDER_UNKNOWN)
                .flatMap(currentStatus -> {
                    log.debug("Current order status: {} for order {}", currentStatus, orderId);

                    // Determinar el mejor estado de finalización basado en el estado actual
                    OrderStatus targetStatus = determineBestCompletionStatus(currentStatus);

                    if (stateMachineService.isValidTransition(currentStatus, targetStatus)) {
                        log.info("Transitioning order {} from {} to {}", orderId, currentStatus, targetStatus);
                        return updateOrderStatus(orderId, targetStatus, correlationId);
                    } else {
                        log.warn("Cannot transition from {} to {} for order {}", currentStatus, targetStatus, orderId);

                        // Buscar estados alternativos progresivos
                        Set<OrderStatus> validNextStates = stateMachineService.getValidNextStates(currentStatus);
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
     * MIGRACIÓN: Maneja errores en la saga de forma centralizada
     */
    protected Mono<Order> handleSagaError(Long orderId, String correlationId, Throwable error) {
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
     * MIGRACIÓN: Determina el topic apropiado para un paso usando OrderStateMachineService
     */
    protected String determineStepTopic(SagaStep step) {
        String topic = step.getTopic();

        // Validación adicional para pasos específicos
        if ("reserveStock".equals(step.getName())) {
            String expectedTopic = stateMachineService.getTopicForTransition(
                    OrderStatus.PAYMENT_CONFIRMED, OrderStatus.STOCK_RESERVED);
            if (expectedTopic != null && !expectedTopic.equals(topic)) {
                log.warn("Topic mismatch for step {}: expected {}, got {}",
                        step.getName(), expectedTopic, topic);
            }
        }

        return topic;
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

                    // MIGRACIÓN: Determinar el topic para el evento de fallo usando OrderStateMachineService
                    String failureTopic = stateMachineService.getTopicForTransition(currentStatus, errorStatus);
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
     * MIGRACIÓN: Crea un paso para reservar stock usando OrderStateMachineService
     */
    protected SagaStep createReserveStockStep(InventoryService inventoryService, Long orderId,
                                              int quantity, String correlationId, String eventId, String externalReference) {

        // MIGRACIÓN: Usar OrderStateMachineService para obtener el topic
        String topic = stateMachineService.getTopicForTransition(
                OrderStatus.PAYMENT_CONFIRMED, OrderStatus.STOCK_RESERVED);

        if (topic == null) {
            topic = EventTopics.getTopicName(OrderStatus.STOCK_RESERVED);
            log.warn("No topic defined for PAYMENT_CONFIRMED -> STOCK_RESERVED transition, using default: {}", topic);
        } else {
            log.debug("Using topic from state machine for PAYMENT_CONFIRMED -> STOCK_RESERVED: {}", topic);
        }

        return SagaStep.builder()
                .name("reserveStock")
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .externalReference(externalReference)
                .topic(topic) // ✅ Usar topic del servicio
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