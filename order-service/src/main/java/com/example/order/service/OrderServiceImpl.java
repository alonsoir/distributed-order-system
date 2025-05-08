package com.example.order.service;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final SagaOrchestratorImpl sagaOrchestrator;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Order> processOrder(String externalReference, int quantity, double amount) {
        // Validación básica
        if (externalReference == null || externalReference.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("External reference cannot be null or empty"));
        }
        if (quantity <= 0) {
            return Mono.error(new IllegalArgumentException("Quantity must be positive"));
        }
        if (amount < 0) {
            return Mono.error(new IllegalArgumentException("Amount must be non-negative"));
        }

        log.info("Starting order with externalReference {} quantity {} amount {}",
                externalReference, quantity, amount);

        // Obtener el CircuitBreaker una sola vez
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("orderProcessing");

        // Comprobar si el CircuitBreaker permite la ejecución
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("Circuit breaker open for order with externalReference {}", externalReference);
            return createFailedOrder("circuit_breaker_open", externalReference);
        }

        return executeWithCircuitBreaker(externalReference, quantity, amount, circuitBreaker);
    }

    /**
     * Ejecuta el flujo de procesamiento de orden con protección de CircuitBreaker
     */
    private Mono<Order> executeWithCircuitBreaker(String externalReference, int quantity, double amount, CircuitBreaker circuitBreaker) {
        // Usamos el API funcional del CircuitBreaker en lugar del transformador de reactor
        return Mono.fromCallable(() -> {
                    // Registramos la llamada en el CircuitBreaker explícitamente
                    circuitBreaker.acquirePermission();
                    try {
                        return sagaOrchestrator.executeOrderSaga(quantity, amount)
                                .timeout(Duration.ofSeconds(15))
                                .doOnError(e -> log.error("Timeout or error in order saga for externalReference {}: {}",
                                        externalReference, e.getMessage()))
                                .onErrorResume(e -> {
                                    // En caso de error, registramos el fallo en el CircuitBreaker
                                    circuitBreaker.onError(0, TimeUnit.NANOSECONDS, e);
                                    return createFailedOrder("global_timeout", externalReference);
                                })
                                .doOnSuccess(order -> {
                                    // En caso de éxito, registramos el éxito en el CircuitBreaker
                                    circuitBreaker.onSuccess(0, TimeUnit.NANOSECONDS);
                                    log.info("Order {} processed successfully: {}", order.id(), order);
                                    meterRegistry.counter("orders_success").increment();
                                });
                    } catch (Exception e) {
                        // En caso de excepción, registramos el fallo en el CircuitBreaker
                        circuitBreaker.onError(0, TimeUnit.NANOSECONDS, e);
                        return createFailedOrder("circuit_breaker_error", externalReference);
                    }
                }).flatMap(mono -> mono)
                .onErrorResume(e -> createFailedOrder("circuit_breaker_error", externalReference));
    }

    /**
     * Crea una orden fallida y registra el evento correspondiente
     */
    private Mono<Order> createFailedOrder(String reason, String externalReference) {
        log.warn("Creating failed order for externalReference {} with reason: {}", externalReference, reason);

        return sagaOrchestrator.createFailedEvent(reason, externalReference)
                .then(Mono.fromCallable(() -> {
                    Order orderFallBack = new Order(null, "failed", externalReference);
                    log.info("Fallback order created with externalReference {}: {}", externalReference, orderFallBack);
                    return orderFallBack;
                }));
    }
}