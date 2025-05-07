package com.example.order.service;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

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

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("orderProcessing");

        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("Circuit breaker open for order with externalReference {}", externalReference);
            return createFailedOrder("circuit_breaker_open", externalReference);
        }

        Mono<Order> orderMono = sagaOrchestrator.executeOrderSaga(quantity, amount)
                .timeout(Duration.ofSeconds(15))
                .doOnError(e -> log.error("Timeout or error in order saga for externalReference {}: {}",
                        externalReference, e.getMessage()))
                .onErrorResume(e -> createFailedOrder("global_timeout", externalReference));

        return orderMono.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .doOnSuccess(order -> {
                    log.info("Order {} processed successfully: {}", order.id(), order);
                    meterRegistry.counter("orders_success").increment();
                })
                .onErrorResume(e -> createFailedOrder("circuit_breaker_error", externalReference));
    }


    private Mono<Order> createFailedOrder(String reason, String externalReference) {
        log.warn("Creating failed order for externalReference {} with reason: {}", externalReference, reason);

        // Primero creamos un OrderFailedEvent genérico (el SagaOrchestratorImpl establecerá los IDs correctos)
        return sagaOrchestrator.createFailedEvent(reason, externalReference)
                .then(Mono.fromCallable(() -> {
                    // Creamos un Order con id=null (se generará en SagaOrchestratorImpl)
                    Order orderFallBack = new Order(null, "failed", externalReference);
                    log.info("Fallback order created with externalReference {}: {}", externalReference, orderFallBack);
                    return orderFallBack;
                }));
    }

}