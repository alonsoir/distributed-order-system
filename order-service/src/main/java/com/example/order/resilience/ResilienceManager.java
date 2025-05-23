package com.example.order.resilience;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.config.MetricsConstants;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Gestor centralizado de patrones de resiliencia (CircuitBreaker, Bulkhead)
 */
@Component
@RequiredArgsConstructor
public class ResilienceManager {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;

    // Caches para evitar recrear instancias
    private final Map<String, CircuitBreaker> circuitBreakerCache = new ConcurrentHashMap<>();
    private final Map<String, Bulkhead> bulkheadCache = new ConcurrentHashMap<>();

    /**
     * Obtiene un CircuitBreaker para una categoría específica
     */
    public CircuitBreaker getCircuitBreaker(CircuitBreakerCategory category) {
        return circuitBreakerCache.computeIfAbsent(category.getName(), name -> {
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(MetricsConstants.CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE)
                    .failureRateThreshold(MetricsConstants.CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD)
                    .waitDurationInOpenState(MetricsConstants.CIRCUIT_BREAKER_WAIT_DURATION)
                    .permittedNumberOfCallsInHalfOpenState(MetricsConstants.CIRCUIT_BREAKER_PERMITTED_CALLS_IN_HALF_OPEN)
                    .recordExceptions(Exception.class)
                    .build();

            return circuitBreakerRegistry.circuitBreaker(name, config);
        });
    }

    /**
     * Obtiene un CircuitBreaker para un paso específico
     */
    public CircuitBreaker getCircuitBreakerForStep(String stepName) {
        return circuitBreakerCache.computeIfAbsent(stepName, name -> {
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(MetricsConstants.CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE)
                    .failureRateThreshold(MetricsConstants.CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD)
                    .waitDurationInOpenState(MetricsConstants.CIRCUIT_BREAKER_WAIT_DURATION)
                    .permittedNumberOfCallsInHalfOpenState(MetricsConstants.CIRCUIT_BREAKER_PERMITTED_CALLS_IN_HALF_OPEN)
                    .recordExceptions(Exception.class)
                    .build();

            return circuitBreakerRegistry.circuitBreaker(name + "-circuit-breaker", config);
        });
    }

    /**
     * Obtiene un Bulkhead para un paso específico
     */
    public Bulkhead getBulkheadForStep(String stepName) {
        return bulkheadCache.computeIfAbsent(stepName, name -> {
            BulkheadConfig config = BulkheadConfig.custom()
                    .maxConcurrentCalls(MetricsConstants.BULKHEAD_MAX_CONCURRENT_CALLS)
                    .maxWaitDuration(MetricsConstants.BULKHEAD_MAX_WAIT_DURATION)
                    .build();

            return bulkheadRegistry.bulkhead(name + "-bulkhead", config);
        });
    }

    /**
     * Aplica protecciones de resiliencia (CircuitBreaker y Bulkhead) a un flujo reactivo
     */
    public <T> Function<Mono<T>, Mono<T>> applyResilience(String stepName) {
        CircuitBreaker circuitBreaker = getCircuitBreakerForStep(stepName);
        Bulkhead bulkhead = getBulkheadForStep(stepName);

        return mono -> mono
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(BulkheadOperator.of(bulkhead));
    }

    /**
     * Aplica protecciones de resiliencia para una categoría específica
     */
    public <T> Function<Mono<T>, Mono<T>> applyResilience(CircuitBreakerCategory category) {
        CircuitBreaker circuitBreaker = getCircuitBreaker(category);

        return mono -> mono
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}