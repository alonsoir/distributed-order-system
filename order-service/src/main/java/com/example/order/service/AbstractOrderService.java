package com.example.order.service;

import com.example.order.config.CircuitBreakerConstants;
import com.example.order.config.MetricsConstants;
import com.example.order.config.ValidationConstants;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

import static com.example.order.config.ValidationConstants.*;

// Abstract class provides protected utility methods and common logic
public abstract class AbstractOrderService {
    private static final Logger log = LoggerFactory.getLogger(AbstractOrderService.class);

    protected final CircuitBreakerRegistry circuitBreakerRegistry;
    protected final MeterRegistry meterRegistry;

    protected AbstractOrderService(CircuitBreakerRegistry circuitBreakerRegistry, MeterRegistry meterRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.meterRegistry = meterRegistry;
    }

    // Protected template methods for subclasses to implement
    protected abstract Mono<Order> executeOrderSaga(int quantity, double amount);
    protected abstract Mono<Void> createFailedEvent(String reason, String externalReference);

    // Protected validation method
    protected Mono<Void> validateOrderParams(String externalReference, int quantity, double amount) {
        if (externalReference == null || externalReference.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException(MSG_EXTERNAL_REF_NULL));
        }
        // Así está en schema-tables.sql
        if (externalReference.length() > 36) {
            return Mono.error(new IllegalArgumentException(MSG_EXTERNAL_REF_TOO_LONG));
        }
        if (quantity <= 0) {
            return Mono.error(new IllegalArgumentException(MSG_QUANTITY_POSITIVE));
        }
        if (amount < 0) {
            return Mono.error(new IllegalArgumentException(MSG_AMOUNT_NON_NEGATIVE));
        }
        return Mono.empty();
    }

    // Protected utility method to handle circuit breaker
    protected Mono<CircuitBreaker> getCircuitBreaker(String circuitBreakerName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName);
        if (!circuitBreaker.tryAcquirePermission()) {
            return Mono.error(new RuntimeException("Circuit breaker open"));
        }
        return Mono.just(circuitBreaker);
    }

    // Protected utility method to record circuit breaker success
    protected void recordSuccess(CircuitBreaker circuitBreaker, Order order) {
        circuitBreaker.onSuccess(0, TimeUnit.NANOSECONDS);
        log.info("Order {} processed successfully: {}", order.id(), order);
        meterRegistry.counter(MetricsConstants.ORDERS_SUCCESS).increment();
    }

    // Protected utility method to record circuit breaker error
    protected void recordError(CircuitBreaker circuitBreaker, Throwable e) {
        circuitBreaker.onError(0, TimeUnit.NANOSECONDS, e);
        log.error("Error in saga: {}", e.getMessage());
    }

    // Protected utility method to determine error reason
    protected String determineErrorReason(Throwable e) {
        if (e instanceof java.util.concurrent.TimeoutException) {
            return CircuitBreakerConstants.GLOBAL_TIMEOUT;
        } else if (e instanceof RuntimeException && "Circuit breaker open".equals(e.getMessage())) {
            return CircuitBreakerConstants.CIRCUIT_BREAKER_OPEN;
        } else {
            return CircuitBreakerConstants.CIRCUIT_BREAKER_ERROR;
        }
    }

    // Protected utility method to create failed order
    // Cambio en AbstractOrderService.java línea 85
    protected Mono<Order> createFailedOrder(String reason, String externalReference) {
        log.warn("Creating failed order for externalReference {} with reason: {}", externalReference, reason);

        // Create the failed order immediately, then trigger the event creation asynchronously
        // CAMBIO: Usar OrderStatus.ORDER_FAILED.getValue() en lugar de "failed"
        Order failedOrder = new Order(null, OrderStatus.ORDER_FAILED.getValue(), externalReference);

        // Return the order immediately, but also trigger the event creation
        return Mono.just(failedOrder)
                .doOnSubscribe(s ->
                        // Fire and forget the event creation - don't wait for it to complete
                        createFailedEvent(reason, externalReference)
                                .subscribe(
                                        success -> log.debug("Failed event created successfully for {}", externalReference),
                                        error -> log.error("Error creating failed event: {}", error.getMessage(), error)
                                )
                );
    }
}