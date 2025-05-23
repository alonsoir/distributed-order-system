package com.example.order.service.v2;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.config.MetricsConstants;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStateMachine;
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
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Implementación base reforzada para orquestadores de sagas con funcionalidad común
 * Versión 2 que elimina las referencias directas a DatabaseClient y usa OrderStateMachine
 */
public abstract class RobustBaseSagaOrchestratorV2 {
    private static final Logger log = LoggerFactory.getLogger(RobustBaseSagaOrchestratorV2.class);

    // Configuración de timeouts
    protected static final Duration DB_OPERATION_TIMEOUT = Duration.ofSeconds(10);
    protected static final Duration EVENT_PUBLISH_TIMEOUT = Duration.ofSeconds(15);
    protected static final Duration SAGA_STEP_TIMEOUT = Duration.ofSeconds(30);
    protected static final Duration GLOBAL_SAGA_TIMEOUT = Duration.ofMinutes(5);

    // Configuración de reintentos
    protected static final int MAX_DB_RETRIES = 3;
    protected static final int MAX_EVENT_RETRIES = 2;
    protected static final Duration INITIAL_BACKOFF = Duration.ofMillis(100);
    protected static final Duration MAX_BACKOFF = Duration.ofSeconds(2);

    protected final TransactionalOperator transactionalOperator;
    protected final MeterRegistry meterRegistry;
    protected final IdGenerator idGenerator;
    protected final ResilienceManager resilienceManager;
    protected final EventPublisher eventPublisher;
    protected final EventRepository eventRepository;

    // MIGRACIÓN: Añadir OrderStateMachine
    protected final OrderStateMachine stateMachine;

    protected RobustBaseSagaOrchestratorV2(
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
        // MIGRACIÓN: Inicializar OrderStateMachine
        this.stateMachine = OrderStateMachine.getInstance();
    }

    /**
     * Maneja un error en un paso de saga con clasificación de errores y estrategia de recuperación
     */
    protected Mono<OrderEvent> handleStepError(SagaStep step, Throwable e, CompensationManager compensationManager) {
        if (step == null) {
            return Mono.error(new IllegalArgumentException("SagaStep cannot be null"));
        }

        if (compensationManager == null) {
            log.error("CompensationManager is null, cannot execute compensation for step {}",
                    step.getName());
            return Mono.error(e);
        }

        ErrorType errorType = classifyError(e);

        log.error("Step {} failed with error type {}: {}",
                step.getName(), errorType, e.getMessage(), e);

        OrderFailedEvent failedEvent = new OrderFailedEvent(
                step.getOrderId(),
                step.getCorrelationId(),
                step.getEventId(),
                SagaStepType.FAILED_EVENT,
                String.format("%s: %s [Type: %s]", e.getClass().getSimpleName(), e.getMessage(), errorType).toString(),
                step.getExternalReference());

        // Actualizar métrica de errores con tipo
        meterRegistry.counter("saga.step.error",
                "step", step.getName(),
                "error_type", errorType.name(),
                "exception", e.getClass().getSimpleName()).increment();

        // Para errores transitorios, podemos reintentar antes de compensar
        if (errorType == ErrorType.TRANSIENT) {
            // La lógica de reintento debería manejarse más arriba en el flujo
            // Aquí solo manejamos el error después de reintentos
            log.warn("Transient error in step {} after retries exhausted", step.getName());
        }

        return publishFailedEvent(failedEvent)
                .then(executeRobustCompensation(step, compensationManager))
                .then(recordStepFailure(step, e, errorType))
                .then(Mono.error(e));
    }

    /**
     * Crea un paso de reserva de stock con validación reforzada usando OrderStateMachine
     */
    protected SagaStep createReserveStockStep(
            InventoryService inventoryService,
            Long orderId,
            int quantity,
            String correlationId,
            String eventId,
            String externalReference) {

        if (inventoryService == null) {
            throw new IllegalArgumentException("InventoryService cannot be null");
        }

        if (orderId == null || correlationId == null || eventId == null) {
            throw new IllegalArgumentException("orderId, correlationId, and eventId cannot be null");
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        // MIGRACIÓN: Usar OrderStateMachine para obtener el topic
        String topic = stateMachine.getTopicNameForTransition(
                OrderStatus.PAYMENT_CONFIRMED, OrderStatus.STOCK_RESERVED);

        // Si no hay un topic específico en el stateMachine, usamos el predeterminado
        if (topic == null) {
            topic = EventTopics.getTopicName(OrderStatus.STOCK_RESERVED);
            log.warn("No topic defined for PAYMENT_CONFIRMED -> STOCK_RESERVED transition, using default: {}", topic);
        } else {
            log.debug("Using topic from state machine for PAYMENT_CONFIRMED -> STOCK_RESERVED: {}", topic);
        }

        return SagaStep.builder()
                .name("reserveStock")
                .topic(topic)
                .stepType(SagaStepType.RESERVE_STOCK)
                .action(() -> inventoryService.reserveStock(orderId, quantity)
                        .timeout(SAGA_STEP_TIMEOUT)
                        .retryWhen(createTransientErrorRetrySpec("reserveStock")))
                .compensation(() -> inventoryService.releaseStock(orderId, quantity)
                        .timeout(SAGA_STEP_TIMEOUT)
                        .retryWhen(createTransientErrorRetrySpec("releaseStock-compensation")))
                .successEvent(eventSuccessId -> new StockReservedEvent(orderId, correlationId, eventId, externalReference, quantity))
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .externalReference(externalReference)
                .build();
    }

    protected Mono<Order> updateOrderStatus(Long orderId, String statusValue, String correlationId) {
        if (orderId == null || statusValue == null || correlationId == null) {
            return Mono.error(new IllegalArgumentException("orderId, status, and correlationId cannot be null"));
        }

        // Convertir el valor de estado a enum
        OrderStatus newStatus;
        try {
            newStatus = OrderStatus.fromValue(statusValue);
        } catch (IllegalArgumentException e) {
            return Mono.error(new IllegalArgumentException("Invalid status value: " + statusValue, e));
        }

        return updateOrderStatus(orderId, newStatus, correlationId);
    }

    protected Mono<Order> updateOrderStatus(Long orderId, OrderStatus newStatus, String correlationId) {
        if (orderId == null || newStatus == null || correlationId == null) {
            return Mono.error(new IllegalArgumentException("orderId, status, and correlationId cannot be null"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "status", newStatus.getValue()
        );

        Tag[] tags = new Tag[] {
                Tag.of("status", newStatus.getValue()),
                Tag.of("correlation_id", correlationId)
        };

        Timer.Sample timer = Timer.start(meterRegistry);

        // Obtener la función de resiliencia fuera del lambda para asegurar que sea final
        Function<Mono<Order>, Mono<Order>> finalResilience = getResilienceFunction();

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.info("Updating order {} status to {}", orderId, newStatus);

            // Primero verificamos si la transición es válida
            return findExistingOrder(orderId)
                    .flatMap(existingOrder -> {
                        // MIGRACIÓN: Usar OrderStateMachine en lugar de OrderStateTransition
                        if (!stateMachine.isValidTransition(existingOrder.status(), newStatus)) {
                            log.warn("Invalid state transition from {} to {} for order {}",
                                    existingOrder.status(), newStatus, orderId);

                            // MIGRACIÓN: Usar OrderStateMachine para obtener estados válidos
                            Set<OrderStatus> validNextStates = stateMachine.getValidNextStates(existingOrder.status());

                            // Buscar una transición alternativa válida
                            OrderStatus alternativeStatus = findBestAlternativeStatus(validNextStates, newStatus);
                            if (alternativeStatus != null) {
                                log.info("Using alternative transition to {} for order {}", alternativeStatus, orderId);
                                return eventRepository.updateOrderStatus(orderId, alternativeStatus, correlationId)
                                        .doOnSuccess(order -> {
                                            log.info("Updated order {} status to {} (alternative)", orderId, alternativeStatus);
                                            timer.stop(meterRegistry.timer("saga.order.status.update", Tags.of(tags)));
                                        })
                                        .doOnError(ex -> {
                                            log.error("Failed to update order {} status: {}", orderId, ex.getMessage(), ex);
                                            meterRegistry.counter("saga.order.status.error", Tags.of(tags)).increment();
                                        })
                                        .transform(finalResilience);
                            }

                            // Opción 1: Retornar la orden sin cambios
                            return Mono.just(existingOrder);

                            // Opción 2: Rechazar con error (descomentar si prefieres este comportamiento)
                            // return Mono.error(new IllegalStateException(
                            //         "Invalid state transition from " + existingOrder.status() + " to " + newStatus));
                        }

                        // La transición es válida, procedemos con la actualización
                        return eventRepository.updateOrderStatus(orderId, newStatus, correlationId)
                                .doOnSuccess(order -> {
                                    log.info("Updated order {} status to {}", orderId, newStatus);
                                    timer.stop(meterRegistry.timer("saga.order.status.update", Tags.of(tags)));
                                })
                                .doOnError(ex -> {
                                    log.error("Failed to update order {} status: {}", orderId, ex.getMessage(), ex);
                                    meterRegistry.counter("saga.order.status.error", Tags.of(tags)).increment();
                                })
                                .transform(finalResilience);
                    });
        });
    }

    /**
     * MIGRACIÓN: Busca el mejor estado alternativo cuando la transición deseada no es válida
     */
    private OrderStatus findBestAlternativeStatus(Set<OrderStatus> validNextStates, OrderStatus desiredStatus) {
        if (validNextStates.isEmpty()) {
            return null;
        }

        // Estrategias de priorización basadas en el tipo de estado deseado
        if (desiredStatus == OrderStatus.ORDER_COMPLETED) {
            // Para completar orden, priorizamos estados progresivos
            if (validNextStates.contains(OrderStatus.ORDER_PROCESSING)) {
                return OrderStatus.ORDER_PROCESSING;
            }
            if (validNextStates.contains(OrderStatus.ORDER_PREPARED)) {
                return OrderStatus.ORDER_PREPARED;
            }
            if (validNextStates.contains(OrderStatus.SHIPPING_PENDING)) {
                return OrderStatus.SHIPPING_PENDING;
            }
        } else if (desiredStatus == OrderStatus.ORDER_FAILED) {
            // Para estados de error, priorizamos excepciones técnicas
            if (validNextStates.contains(OrderStatus.TECHNICAL_EXCEPTION)) {
                return OrderStatus.TECHNICAL_EXCEPTION;
            }
            if (validNextStates.contains(OrderStatus.MANUAL_REVIEW)) {
                return OrderStatus.MANUAL_REVIEW;
            }
        }

        // Si no hay una alternativa específica, devolver null
        return null;
    }

    // Método separado para obtener la función de resiliencia
    private Function<Mono<Order>, Mono<Order>> getResilienceFunction() {
        // Default a función identidad
        Function<Mono<Order>, Mono<Order>> resilience = Function.identity();

        try {
            Function<Mono<Order>, Mono<Order>> tempResilience =
                    resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS);
            if (tempResilience != null) {
                resilience = tempResilience;
            }
        } catch (Exception e) {
            log.warn("Error al obtener transformer de resiliencia para updateOrderStatus: {}, usando identidad", e.getMessage());
        }

        return resilience;
    }

    /**
     * Insertar registro de auditoría para actualización de estado
     */
    protected Mono<Void> insertUpdateStatusAuditLog(Long orderId, OrderStatus status, String correlationId) {
        return eventRepository.insertStatusAuditLog(orderId, status, correlationId);
    }

    /**
     * Clasificación de tipos de errores para decisiones de reintento y compensación
     */
    protected enum ErrorType {
        TRANSIENT,       // Errores temporales que podrían resolverse con reintentos
        VALIDATION,      // Errores de validación o lógica de negocio
        RESOURCE,        // Errores permanentes de recursos
        COMMUNICATION,   // Errores de comunicación con servicios externos
        UNKNOWN          // Errores no clasificados
    }

    /**
     * Clasificar el tipo de error para estrategia de manejo
     */
    protected ErrorType classifyError(Throwable e) {
        if (e instanceof java.util.concurrent.TimeoutException ||
                e instanceof java.net.ConnectException ||
                e instanceof io.r2dbc.spi.R2dbcTransientResourceException) {
            return ErrorType.TRANSIENT;
        } else if (e instanceof IllegalArgumentException ||
                e instanceof IllegalStateException ||
                e instanceof java.util.NoSuchElementException) {
            return ErrorType.VALIDATION;
        } else if (e instanceof io.r2dbc.spi.R2dbcNonTransientResourceException) {
            return ErrorType.RESOURCE;
        } else if (e instanceof java.net.SocketException ||
                e instanceof reactor.netty.http.client.PrematureCloseException) {
            return ErrorType.COMMUNICATION;
        } else {
            return ErrorType.UNKNOWN;
        }
    }

    protected Mono<Void> executeRobustCompensation(SagaStep step, CompensationManager compensationManager) {
        if (step == null || step.getCompensation() == null) {
            log.warn("No compensation defined for step {}",
                    step != null ? step.getName() : "null");
            return Mono.empty();
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "stepName", step.getName(),
                "orderId", step.getOrderId().toString(),
                "correlationId", step.getCorrelationId(),
                "eventId", step.getEventId(),
                "operation", "compensation"
        );

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.info("Executing compensation for step {} of order {}",
                    step.getName(), step.getOrderId());

            Timer.Sample timer = Timer.start(meterRegistry);

            // Primero, registramos el inicio de la compensación
            Mono<Void> startLog = eventRepository.insertCompensationLog(
                    step.getName(), step.getOrderId(), step.getCorrelationId(), step.getEventId(), OrderStatus.ORDER_CREATED);

            // Luego ejecutamos la compensación con verificación de null
            Mono<Void> executeComp = compensationManager.executeCompensation(step);
            if (executeComp == null) {
                log.warn("CompensationManager.executeCompensation returned null for step {}", step.getName());
                executeComp = Mono.empty(); // Fallback seguro
            }

            Mono<Void> safeExecuteComp = executeComp
                    .timeout(SAGA_STEP_TIMEOUT)
                    .doOnSuccess(v -> {
                        log.info("Compensation completed successfully for step {}", step.getName());
                        timer.stop(meterRegistry.timer("saga.compensation",
                                "step", step.getName(),
                                "result", "success"));
                    })
                    .doOnError(e -> {
                        log.error("Compensation failed for step {}: {}",
                                step.getName(), e.getMessage(), e);
                        meterRegistry.counter("saga.compensation.error",
                                "step", step.getName()).increment();
                    });

            // Finalmente, registramos la finalización (exitosa o fallida)
            Mono<Void> completionLog = Mono.defer(() ->
                    eventRepository.insertCompensationLog(
                            step.getName(), step.getOrderId(), step.getCorrelationId(),
                            step.getEventId(), OrderStatus.ORDER_COMPLETED)
            );

            // Encadenamos las operaciones, con manejo de errores
            return startLog
                    .then(safeExecuteComp)
                    .then(completionLog)
                    .onErrorResume(e -> {
                        // En caso de error, registramos el fallo y alertamos
                        return eventRepository.insertCompensationLog(
                                        step.getName(), step.getOrderId(), step.getCorrelationId(),
                                        step.getEventId(), OrderStatus.valueOf(OrderStatus.ORDER_FAILED + e.getMessage()))
                                .then(triggerCompensationFailureAlert(step, e));
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    /**
     * MIGRACIÓN: Método actualizado para usar OrderStateMachine
     */
    protected boolean isValidStateTransition(Order order, OrderStatus newStatus) {
        return stateMachine.isValidTransition(order.status(), newStatus);
    }

    /**
     * Crea una especificación de reintento para errores transitorios en base de datos
     */
    protected Retry createDatabaseRetrySpec(String operation) {
        return Retry.backoff(MAX_DB_RETRIES, INITIAL_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .filter(e -> classifyError(e) == ErrorType.TRANSIENT)
                .doBeforeRetry(retrySignal -> {
                    log.warn("Retrying database operation {} after error, attempt: {}/{}",
                            operation, retrySignal.totalRetries() + 1, MAX_DB_RETRIES);
                    meterRegistry.counter("saga.db.retry",
                            "operation", operation).increment();
                });
    }

    /**
     * Crea una especificación de reintento para errores transitorios en operaciones generales
     */
    protected Retry createTransientErrorRetrySpec(String operation) {
        return Retry.backoff(MAX_EVENT_RETRIES, INITIAL_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .filter(e -> classifyError(e) == ErrorType.TRANSIENT ||
                        classifyError(e) == ErrorType.COMMUNICATION)
                .doBeforeRetry(retrySignal -> {
                    log.warn("Retrying operation {} after error, attempt: {}/{}",
                            operation, retrySignal.totalRetries() + 1, MAX_EVENT_RETRIES);
                    meterRegistry.counter("saga.operation.retry",
                            "operation", operation).increment();
                });
    }

    /**
     * Registra log de compensación en base de datos
     */
    protected Mono<Void> insertCompensationLog(SagaStep step, OrderStatus status) {
        return eventRepository.insertCompensationLog(
                step.getName(), step.getOrderId(), step.getCorrelationId(), step.getEventId(), status);
    }

    protected Mono<Void> recordStepFailure(SagaStep step, Throwable error, ErrorType errorType) {
        String exceptionName = error.getClass().getSimpleName(); // Usa getSimpleName() en lugar de getName()
        return eventRepository.recordStepFailure(
                step.getName(), step.getOrderId(), step.getCorrelationId(), step.getEventId(),
                error.getMessage(), exceptionName, errorType.name());
    }

    /**
     * Envía alerta para fallo de compensación que requiere intervención
     */
    protected Mono<Void> triggerCompensationFailureAlert(SagaStep step, Throwable error) {
        String alertMessage = String.format(
                "CRITICAL: Compensation failed for step %s, order %s: %s [%s]",
                step.getName(), step.getOrderId(), error.getMessage(), error.getClass().getSimpleName());

        log.error(alertMessage);

        // Aquí se implementaría integración real con sistema de alertas
        // Para este ejemplo solo incrementamos métricas
        meterRegistry.counter("saga.critical.compensation.failure",
                "step", step.getName()).increment();

        return Mono.empty(); // Placeholder para futura implementación
    }

    protected Mono<Void> insertOrderData(Long orderId, String correlationId, String eventId, OrderEvent event) {
        if (orderId == null || correlationId == null || eventId == null || event == null) {
            return Mono.error(new IllegalArgumentException("Required parameters cannot be null"));
        }

        // Asegurar que resilienceManager.applyResilience no cause NullPointerException
        Function<Mono<Void>, Mono<Void>> resilience = Function.identity(); // Default a función identidad
        try {
            Function<Mono<Void>, Mono<Void>> tempResilience =
                    resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS);
            if (tempResilience != null) {
                resilience = tempResilience;
            }
        } catch (Exception e) {
            log.warn("Error al obtener transformer de resiliencia para insertOrderData: {}, usando identidad", e.getMessage());
        }

        return eventRepository.saveOrderData(orderId, correlationId, eventId, event)
                .transform(resilience); // Usamos la función resilience que ahora es segura
    }

    /**
     * Verifica si un evento ya ha sido procesado para garantizar idempotencia
     */
    public Mono<Boolean> isEventAlreadyProcessed(String eventId) {
        if (eventId == null) {
            return Mono.error(new IllegalArgumentException("eventId cannot be null"));
        }

        return eventRepository.isEventProcessed(eventId);
    }

    /**
     * Encuentra una orden existente por ID
     */
    protected Mono<Order> findExistingOrder(Long orderId) {
        if (orderId == null) {
            return Mono.error(new IllegalArgumentException("orderId cannot be null"));
        }

        return eventRepository.findOrderById(orderId);
    }

    /**
     * Maneja un error al crear una orden con clasificación de errores
     */
    protected Mono<Order> handleCreateOrderError(Long orderId, String correlationId, String eventId, String externalReference, Throwable e) {
        ErrorType errorType = classifyError(e);

        log.error("Error creating order {}: {} [Type: {}]",
                orderId, e.getMessage(), errorType, e);

        meterRegistry.counter("saga.order.creation.error",
                "error_type", errorType.name(),
                "error_class", e.getClass().getSimpleName()).increment();

        OrderFailedEvent failedEvent = new OrderFailedEvent(
                orderId,
                correlationId,
                eventId, SagaStepType.CREATE_ORDER,
                String.format("%s: %s [Type: %s]", e.getClass().getSimpleName(), e.getMessage(), errorType),
                externalReference);

        return publishFailedEvent(failedEvent)
                .then(recordStepFailure(createDummyStepForError("createOrder", orderId, correlationId, eventId), e, errorType))
                .then(Mono.just(new Order(orderId, "failed", correlationId)));
    }

    /**
     * Crea un paso dummy para registro de errores en operaciones que no son pasos
     */
    protected SagaStep createDummyStepForError(String operationName, Long orderId, String correlationId, String eventId) {
        return SagaStep.builder()
                .name(operationName)
                .topic("dummy.topic")
                .stepType(SagaStepType.FAILED_EVENT)
                .orderId(orderId)
                .correlationId(correlationId)
                .eventId(eventId)
                .build();
    }

    protected Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic) {
        if (event == null || step == null || topic == null) {
            return Mono.error(new IllegalArgumentException("event, step, and topic cannot be null"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "eventId", event.getEventId(),
                "eventType", event.getType().name(),
                "step", step,
                "topic", topic,
                "orderId", event.getOrderId().toString(),
                "correlationId", event.getCorrelationId()
        );

        Tag[] tags = new Tag[] {
                Tag.of("step", step),
                Tag.of("topic", topic),
                Tag.of("event_type", event.getType().name())
        };

        Timer.Sample timer = Timer.start(meterRegistry);

        return ReactiveUtils.withContextAndMetrics(
                context,
                () -> {
                    log.info("Publishing {} event {} for step {} to topic {}",
                            event.getType(), event.getEventId(), step, topic);

                    // Mejoramos el manejo de este caso:
                    Function<Mono<OrderEvent>, Mono<OrderEvent>> resilience = Function.identity(); // Default a función identidad
                    try {
                        Function<Mono<OrderEvent>, Mono<OrderEvent>> tempResilience =
                                resilienceManager.applyResilience(CircuitBreakerCategory.EVENT_PUBLISHING);
                        if (tempResilience != null) {
                            resilience = tempResilience;
                        }
                    } catch (Exception e) {
                        log.warn("Error al obtener transformer de resiliencia: {}, usando identidad", e.getMessage());
                    }

                    return eventRepository.saveEventHistory(
                                    event.getEventId(), event.getCorrelationId(), event.getOrderId(),
                                    event.getType().name(), step, "ATTEMPT")
                            .then(eventPublisher.publishEvent(event, step, topic)
                                    .map(EventPublishOutcome::getEvent)
                                    .timeout(EVENT_PUBLISH_TIMEOUT)
                                    .retryWhen(createTransientErrorRetrySpec("publish-" + event.getType()))
                                    .transform(resilience)
                                    .doOnSuccess(v -> {
                                        log.info("Published event {} for step {}", event.getEventId(), step);
                                        eventRepository.saveEventHistory(
                                                event.getEventId(), event.getCorrelationId(), event.getOrderId(),
                                                event.getType().name(), step, "SUCCESS").subscribe();
                                        timer.stop(meterRegistry.timer("saga.event.publish.time", Tags.of(tags)));
                                    })
                                    .doOnError(e -> {
                                        log.error("Failed to publish event {} for step {}: {}",
                                                event.getEventId(), step, e.getMessage(), e);
                                        eventRepository.saveEventHistory(
                                                event.getEventId(), event.getCorrelationId(), event.getOrderId(),
                                                event.getType().name(), step, "FAILURE: " + e.getMessage()).subscribe();
                                        meterRegistry.counter("saga.event.publish.error", Tags.of(tags)).increment();
                                    }));
                },
                meterRegistry,
                MetricsConstants.METRIC_EVENT_PUBLISH,
                tags
        );
    }

    /**
     * Guarda historial de eventos para auditoría
     */
    protected Mono<Void> saveEventHistory(OrderEvent event, String operation, String outcome) {
        return eventRepository.saveEventHistory(
                event.getEventId(), event.getCorrelationId(), event.getOrderId(),
                event.getType().name(), operation, outcome);
    }

    /**
     * Publica un evento de fallo usando OrderStateMachine para determinar el topic
     */
    protected Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        if (event == null) {
            return Mono.error(new IllegalArgumentException("Failed event cannot be null"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "eventId", event.getEventId(),
                "correlationId", event.getCorrelationId(),
                "orderId", event.getOrderId().toString(),
                "reason", event.getReason()
        );

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.info("Publishing failure event for order {} with reason: {}",
                    event.getOrderId(), event.getReason());

            // MIGRACIÓN: Obtener el estado actual para determinar el topic adecuado usando OrderStateMachine
            return findExistingOrder(event.getOrderId())
                    .defaultIfEmpty(new Order(event.getOrderId(), OrderStatus.ORDER_UNKNOWN.getValue(), event.getCorrelationId()))
                    .flatMap(existingOrder -> {
                        // Determinar el topic adecuado usando OrderStateMachine
                        String failureTopic = stateMachine.getTopicNameForTransition(
                                existingOrder.status(), OrderStatus.ORDER_FAILED);

                        if (failureTopic == null) {
                            failureTopic = EventTopics.getTopicName(OrderStatus.ORDER_FAILED);
                            log.warn("No topic defined for failure transition {} -> ORDER_FAILED, using default: {}",
                                    existingOrder.status(), failureTopic);
                        } else {
                            log.debug("Using topic from state machine for {} -> ORDER_FAILED: {}",
                                    existingOrder.status(), failureTopic);
                        }

                        return publishEvent(event, "failedEvent", failureTopic);
                    })
                    .onErrorResume(e -> {
                        log.error("Failed to publish failure event: {}", e.getMessage(), e);
                        // Crítico: no pudimos publicar evento de fallo
                        meterRegistry.counter("saga.critical.publish_failed_event.error").increment();
                        return Mono.empty(); // No propagamos el error para no bloquear compensación
                    })
                    .then();
        });
    }
}