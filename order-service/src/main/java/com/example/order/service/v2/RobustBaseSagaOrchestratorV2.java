package com.example.order.service.v2;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.config.SagaConfig;
import com.example.order.domain.Order;
import com.example.order.domain.DeliveryMode;
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
import java.util.function.Function;

/**
 * Implementación base reforzada para orquestadores de sagas con funcionalidad común
 * Versión 2 que elimina las referencias directas a DatabaseClient
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
     * Crea un paso de reserva de stock con validación reforzada
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

        return SagaStep.builder()
                .name("reserveStock")
                .topic(EventTopics.STOCK_RESERVED.getTopic())
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

    protected Mono<Order> updateOrderStatus(Long orderId, String status, String correlationId) {
        if (orderId == null || status == null || correlationId == null) {
            return Mono.error(new IllegalArgumentException("orderId, status, and correlationId cannot be null"));
        }

        Map<String, String> context = ReactiveUtils.createContext(
                "orderId", orderId.toString(),
                "correlationId", correlationId,
                "status", status
        );

        Tag[] tags = new Tag[] {
                Tag.of("status", status),
                Tag.of("correlation_id", correlationId)
        };

        Timer.Sample timer = Timer.start(meterRegistry);

        return ReactiveUtils.withDiagnosticContext(context, () -> {
            log.info("Updating order {} status to {}", orderId, status);

            // Asegurar que resilienceManager.applyResilience no cause NullPointerException
            Function<Mono<Order>, Mono<Order>> resilience = Function.identity(); // Default a función identidad
            try {
                Function<Mono<Order>, Mono<Order>> tempResilience =
                        resilienceManager.applyResilience(CircuitBreakerCategory.DATABASE_OPERATIONS);
                if (tempResilience != null) {
                    resilience = tempResilience;
                }
            } catch (Exception e) {
                log.warn("Error al obtener transformer de resiliencia para updateOrderStatus: {}, usando identidad", e.getMessage());
            }

            return eventRepository.updateOrderStatus(orderId, status, correlationId)
                    .doOnSuccess(order -> {
                        log.info("Updated order {} status to {}", orderId, status);
                        timer.stop(meterRegistry.timer("saga.order.status.update", Tags.of(tags)));
                    })
                    .doOnError(e -> {
                        log.error("Failed to update order {} status: {}", orderId, e.getMessage(), e);
                        meterRegistry.counter("saga.order.status.error", Tags.of(tags)).increment();
                    })
                    .transform(resilience); // Usamos la función resilience que ahora es segura
        });
    }

    /**
     * Insertar registro de auditoría para actualización de estado
     */
    protected Mono<Void> insertUpdateStatusAuditLog(Long orderId, String status, String correlationId) {
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

    /**
     * Ejecuta compensación con manejo de errores y reintentos
     */
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

            return eventRepository.insertCompensationLog(step.getName(), step.getOrderId(), step.getCorrelationId(), step.getEventId(), "STARTED")
                    .then(compensationManager.executeCompensation(step))
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
                    })
                    .then(eventRepository.insertCompensationLog(step.getName(), step.getOrderId(), step.getCorrelationId(), step.getEventId(), "COMPLETED"))
                    .onErrorResume(e -> {
                        return eventRepository.insertCompensationLog(step.getName(), step.getOrderId(), step.getCorrelationId(), step.getEventId(), "FAILED: " + e.getMessage())
                                .then(triggerCompensationFailureAlert(step, e))
                                .then(Mono.empty()); // No propagamos el error para no bloquear
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        });
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
    protected Mono<Void> insertCompensationLog(SagaStep step, String status) {
        return eventRepository.insertCompensationLog(
                step.getName(), step.getOrderId(), step.getCorrelationId(), step.getEventId(), status);
    }

    /**
     * Registra un fallo de paso en base de datos para análisis posterior
     */
    protected Mono<Void> recordStepFailure(SagaStep step, Throwable error, ErrorType errorType) {
        return eventRepository.recordStepFailure(
                step.getName(), step.getOrderId(), step.getCorrelationId(), step.getEventId(),
                error.getMessage(), error.getClass().getName(), errorType.name());
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
                SagaConfig.METRIC_EVENT_PUBLISH,
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
     * Publica un evento de fallo
     */
    protected Mono<Void> publishFailedEvent(OrderFailedEvent event) {
        return publishEvent(event, "failedEvent", EventTopics.ORDER_FAILED.getTopic())
                .onErrorResume(e -> {
                    log.error("Failed to publish failure event: {}", e.getMessage(), e);
                    // Métrica crítica - fallo al publicar evento de fallo
                    meterRegistry.counter("saga.critical.event.failure").increment();
                    return Mono.empty(); // No propagamos el error para no bloquear la compensación
                })
                .then();
    }
}