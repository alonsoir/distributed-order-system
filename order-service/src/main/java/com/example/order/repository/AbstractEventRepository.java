package com.example.order.repository;

import com.example.order.config.MetricsConstants;
import com.example.order.domain.DeliveryMode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.retry.Retry;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Clase base abstracta que proporciona funcionalidades comunes para implementaciones
 * de EventRepository con soporte para métricas, trazabilidad, reintentos y circuit breakers.
 */
public abstract class AbstractEventRepository implements EventRepository {

    protected static final Logger log = LoggerFactory.getLogger(AbstractEventRepository.class);

    // Configuraciones por defecto (pueden ser sobreescritas por propiedades)
    protected static final int DEFAULT_MAX_RETRIES = MetricsConstants.DEFAULT_MAX_RETRIES;
    protected static final Duration DEFAULT_RETRY_BACKOFF = MetricsConstants.DEFAULT_RETRY_BACKOFF;
    protected static final Duration DEFAULT_OPERATION_TIMEOUT = MetricsConstants.DEFAULT_OPERATION_TIMEOUT;
    protected static final int DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD = MetricsConstants.DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD;
    protected static final Duration DEFAULT_CIRCUIT_BREAKER_WAIT_DURATION = MetricsConstants.CIRCUIT_BREAKER_WAIT_DURATION;

    // Componentes para métricas y resiliencia
    protected final MeterRegistry meterRegistry;
    protected final CircuitBreakerRegistry circuitBreakerRegistry;
    protected final Optional<Tracer> tracer;

    // Propiedades configurables (pueden venir de EventRepositoryProperties)
    protected final int maxRetries;
    protected final Duration retryBackoff;
    protected final Duration operationTimeout;
    protected final boolean enableCircuitBreaker;

    /**
     * Constructor base con todos los componentes de soporte
     */
    protected AbstractEventRepository(MeterRegistry meterRegistry,
                                      CircuitBreakerRegistry circuitBreakerRegistry,
                                      Optional<Tracer> tracer,
                                      int maxRetries,
                                      Duration retryBackoff,
                                      Duration operationTimeout,
                                      boolean enableCircuitBreaker) {
        this.meterRegistry = meterRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.tracer = tracer;
        this.maxRetries = maxRetries;
        this.retryBackoff = retryBackoff;
        this.operationTimeout = operationTimeout;
        this.enableCircuitBreaker = enableCircuitBreaker;
    }

    /**
     * Constructor que usa valores por defecto
     */
    protected AbstractEventRepository(MeterRegistry meterRegistry, CircuitBreakerRegistry circuitBreakerRegistry) {
        this(meterRegistry,
                circuitBreakerRegistry,
                Optional.empty(),
                DEFAULT_MAX_RETRIES,
                DEFAULT_RETRY_BACKOFF,
                DEFAULT_OPERATION_TIMEOUT,
                true);
    }

    /**
     * Ejecuta una operación con soporte para trazabilidad, métricas, timeouts,
     * reintentos y circuit breaker.
     *
     * @param operationName Nombre de la operación (usado para logging y métricas)
     * @param operation Operación reactiva a ejecutar
     * @param shouldRetry Función que determina si un error debe reintentarse
     * @return Mono con el resultado de la operación o error
     */
    protected <T> Mono<T> executeOperation(String operationName,
                                           Mono<T> operation,
                                           Function<Throwable, Boolean> shouldRetry) {
        // Capturar el tiempo de inicio para métricas
        Timer.Sample sample = Timer.start(meterRegistry);

        // Crear un contexto para enriquecer logs y métricas
        Map<String, String> logContext = createLogContext(operationName, null, null);

        return operation
                // Aplicar timeout para evitar operaciones bloqueadas indefinidamente
                .timeout(operationTimeout)

                // Registrar éxito
                .doOnSuccess(result -> {
                    meterRegistry.counter("repository.operations",
                            "operation", operationName,
                            "result", "success").increment();
                })

                // Registrar errores con detalles
                .doOnError(e -> {
                    // Añadir contexto a logs
                    try (MDC.MDCCloseable closeable = MDC.putCloseable("operation", operationName)) {
                        if (e instanceof TimeoutException) {
                            log.error("Operation timed out after {}ms: {}",
                                    operationTimeout.toMillis(), operationName);
                        } else {
                            log.error("Error in operation {}: {} - {}",
                                    operationName, e.getClass().getSimpleName(), e.getMessage());
                        }
                    }

                    // Registrar métricas de error
                    meterRegistry.counter("repository.errors",
                            "operation", operationName,
                            "error_type", e.getClass().getSimpleName()).increment();
                })

                // Añadir trazabilidad si está disponible
                .contextWrite(contextParam -> {
                    Context contextResult = contextParam;
                    // Si hay un tracer disponible, capturar el trace ID actual
                    if (tracer.isPresent()) {
                        Tracer tracerInstance = tracer.get();
                        if (tracerInstance.currentSpan() != null) {
                            contextResult = contextResult.put("traceId", tracerInstance.currentSpan().context().traceId());
                        }
                    }
                    return contextResult;
                })

                // Aplicar reintentos para errores transitorios
                .retryWhen(Retry.backoff(maxRetries, retryBackoff)
                        .filter(throwable -> shouldRetry.apply(throwable))
                        .doBeforeRetry(rs -> {
                            meterRegistry.counter("repository.retries",
                                    "operation", operationName,
                                    "attempt", String.valueOf(rs.totalRetries() + 1)).increment();
                            log.debug("Retrying operation {} after error: {} (attempt {})",
                                    operationName, rs.failure().getMessage(), rs.totalRetries() + 1);
                        }))

                // Aplicar circuit breaker si está habilitado
                .transform(mono -> {
                    if (enableCircuitBreaker) {
                        CircuitBreaker cb = getOrCreateCircuitBreaker(operationName);
                        return mono.transformDeferred(CircuitBreakerOperator.of(cb));
                    }
                    return mono;
                })

                // Registrar métricas de tiempo de respuesta
                .doFinally(signalType ->
                        sample.stop(meterRegistry.timer("repository.operation.time",
                                "operation", operationName,
                                "result", signalType.toString())));
    }

    /**
     * Ejecuta una operación dentro de un bloqueo transaccional.
     *
     * @param resourceId ID del recurso a bloquear
     * @param correlationId ID de correlación
     * @param timeoutSeconds Tiempo máximo para adquirir el bloqueo
     * @param operation Operación a ejecutar bajo el bloqueo
     * @return Resultado de la operación o error si no se pudo adquirir el bloqueo
     */
    protected <T> Mono<T> executeWithLock(String resourceId, String correlationId,
                                          int timeoutSeconds, Mono<T> operation) {
        String operationName = "lock_" + resourceId;
        Timer.Sample lockTimer = Timer.start(meterRegistry);

        return acquireTransactionLock(resourceId, correlationId, timeoutSeconds)
                .flatMap(acquired -> {
                    if (!acquired) {
                        meterRegistry.counter("repository.locks.acquisition_failed",
                                "resource", resourceId).increment();
                        log.warn("Failed to acquire lock for resource {} with correlationId {}",
                                resourceId, correlationId);
                        return Mono.error(new ResourceLockedException(
                                "Could not acquire lock for resource: " + resourceId));
                    }

                    meterRegistry.counter("repository.locks.acquired",
                            "resource", resourceId).increment();
                    lockTimer.stop(meterRegistry.timer("repository.lock.acquisition_time",
                            "resource", resourceId));

                    // Ejecutar la operación con el bloqueo adquirido
                    return operation
                            .doFinally(signal -> {
                                // Siempre liberar el bloqueo al finalizar
                                Timer.Sample unlockTimer = Timer.start(meterRegistry);
                                releaseTransactionLock(resourceId, correlationId)
                                        .doOnSuccess(v -> {
                                            meterRegistry.counter("repository.locks.released",
                                                    "resource", resourceId).increment();
                                            unlockTimer.stop(meterRegistry.timer(
                                                    "repository.lock.release_time",
                                                    "resource", resourceId));
                                        })
                                        .doOnError(e -> {
                                            log.error("Error releasing lock for resource {}: {}",
                                                    resourceId, e.getMessage());
                                            meterRegistry.counter("repository.locks.release_failed",
                                                    "resource", resourceId).increment();
                                        })
                                        .subscribe();
                            });
                });
    }

    /**
     * Crea un bloqueo específico para una orden
     *
     * @param orderId ID de la orden
     * @param correlationId ID de correlación
     * @param timeoutSeconds Tiempo máximo para adquirir el bloqueo
     * @param operation Operación a ejecutar bajo el bloqueo
     * @return Resultado de la operación o error
     */
    protected <T> Mono<T> executeWithOrderLock(Long orderId, String correlationId,
                                               int timeoutSeconds, Mono<T> operation) {
        String resourceId = "order:" + orderId;
        return executeWithLock(resourceId, correlationId, timeoutSeconds, operation);
    }

    /**
     * Crea un bloqueo específico para un evento
     */
    protected <T> Mono<T> executeWithEventLock(String eventId, String correlationId,
                                               int timeoutSeconds, Mono<T> operation) {
        String resourceId = "event:" + eventId;
        return executeWithLock(resourceId, correlationId, timeoutSeconds, operation);
    }

    /**
     * Determina si un error es transitorio y puede reintentarse
     *
     * @param throwable Error a evaluar
     * @return true si el error es transitorio, false en caso contrario
     */
    protected boolean isTransientError(Throwable throwable) {
        // Errores de validación y negocio no deben reintentarse
        if (throwable instanceof jakarta.validation.ConstraintViolationException ||
                throwable instanceof IllegalArgumentException ||
                throwable instanceof DuplicateKeyException ||
                throwable instanceof DataIntegrityViolationException) {
            return false;
        }

        // Errores de conexión y transitorios explícitos sí deben reintentarse
        if (throwable instanceof ConnectException ||
                throwable instanceof SocketTimeoutException ||
                throwable instanceof TimeoutException ||
                throwable instanceof TransientDataAccessException ||
                (throwable instanceof io.r2dbc.spi.R2dbcException)) {
            // Nota: Hemos eliminado la comprobación de isTransient() que no existía
            return true;
        }

        // Examinar la causa raíz si está encapsulada
        if (throwable.getCause() != null && throwable != throwable.getCause()) {
            return isTransientError(throwable.getCause());
        }

        // Analizar el mensaje para detectar errores probablemente transitorios
        String message = throwable.getMessage() != null ? throwable.getMessage().toLowerCase() : "";
        return message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("unavailable") ||
                message.contains("too many connections") ||
                message.contains("deadlock") ||
                message.contains("lock timeout") ||
                message.contains("could not serialize");
    }

    /**
     * Crea un contexto enriquecido para logs y métricas
     */
    protected Map<String, String> createLogContext(String operation, Long orderId, String correlationId) {
        Map<String, String> context = new HashMap<>();
        context.put("operation", operation);

        if (orderId != null) {
            context.put("orderId", orderId.toString());
        }

        if (correlationId != null) {
            context.put("correlationId", correlationId);
        }

        // Añadir trace ID si está disponible
        if (tracer.isPresent()) {
            Tracer tracerInstance = tracer.get();
            if (tracerInstance.currentSpan() != null) {
                context.put("traceId", tracerInstance.currentSpan().context().traceId());
            }
        }

        return context;
    }

    /**
     * Obtiene o crea un circuit breaker para una operación específica
     */
    protected CircuitBreaker getOrCreateCircuitBreaker(String operationName) {
        return circuitBreakerRegistry.circuitBreaker("event-repository-" + operationName);
    }

    /**
     * Clase de excepción para cuando no se puede adquirir un bloqueo
     */
    public static class ResourceLockedException extends RuntimeException {
        public ResourceLockedException(String message) {
            super(message);
        }

        public ResourceLockedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}