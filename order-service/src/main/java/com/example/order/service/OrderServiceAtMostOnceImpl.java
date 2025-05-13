package com.example.order.service;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service("orderServiceAtMostOnce")
public class OrderServiceAtMostOnceImpl extends AbstractOrderService implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceAtLeastOnceImpl.class);
    private final SagaOrchestrator sagaOrchestrator;

    public OrderServiceAtMostOnceImpl(CircuitBreakerRegistry circuitBreakerRegistry,
                                       MeterRegistry meterRegistry,
                                       @Qualifier("sagaOrchestratorImpl2") SagaOrchestrator sagaOrchestrator) {
        super(circuitBreakerRegistry, meterRegistry);
        this.sagaOrchestrator = sagaOrchestrator;
    }

    @Override
    public Mono<Order> processOrder(String externalReference, int quantity, double amount) {
        log.info("Starting order with externalReference {} quantity {} amount {}",
                externalReference, quantity, amount);

        return validateOrderParams(externalReference, quantity, amount)
                .then(getCircuitBreaker("orderProcessing"))
                .flatMap(circuitBreaker ->
                        executeOrderSaga(quantity, amount)
                                .doOnSuccess(order -> recordSuccess(circuitBreaker, order))
                                .doOnError(e -> recordError(circuitBreaker, e))
                                .onErrorResume(e -> {
                                    String reason = determineErrorReason(e);
                                    log.warn("Creating failed order due to: {}", reason);
                                    return createFailedOrder(reason, externalReference);
                                })
                )
                .onErrorResume(e -> {
                    // Handle the case where getCircuitBreaker throws an error
                    if (e instanceof RuntimeException && "Circuit breaker open".equals(e.getMessage())) {
                        return createFailedOrder("circuit_breaker_open", externalReference);
                    }
                    return Mono.error(e);
                });
    }

    @Override
    protected Mono<Order> executeOrderSaga(int quantity, double amount) {
        return sagaOrchestrator.executeOrderSaga(quantity, amount);
    }

    @Override
    protected Mono<Void> createFailedEvent(String reason, String externalReference) {
        return sagaOrchestrator.createFailedEvent(reason, externalReference);
    }
}