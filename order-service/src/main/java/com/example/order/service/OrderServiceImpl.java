package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.model.SagaStep;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final SagaOrchestrator sagaOrchestrator;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public Mono<Order> processOrder(Long orderId, int quantity, double amount) {
        String correlationId = UUID.randomUUID().toString();
        log.info("Starting order {} with correlationId {}", orderId, correlationId);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("orderProcessing");

        Mono<Order> orderMono = sagaOrchestrator.executeOrderSaga(orderId, quantity, amount, correlationId)
                .timeout(Duration.ofSeconds(15))
                .doOnError(e -> log.error("Timeout or error in order saga for order {}: {}", orderId, e.getMessage()))
                .onErrorResume(e -> onTimeout(orderId, correlationId, "global_timeout"));

        return orderMono.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .doOnSuccess(order -> log.info("Order {} processed successfully: {}", orderId, order))
                .doOnError(e -> log.error("Circuit breaker error for order {}: {}", orderId, e.getMessage()));
    }

    @Override
    public Mono<Order> createOrder(Long orderId, String correlationId) {
        log.info("Creating order {} with correlationId {}", orderId, correlationId);

        return null;
    }

    @Override
    public Mono<Void> publishEvent(OrderEvent event) {
        return null;
    }

    @Override
    public Mono<OrderEvent> executeStep(SagaStep step) {
        return null;
    }

    private Mono<Order> onTimeout(Long orderId, String correlationId, String reason) {
        log.error("Timeout for order {}: {}", orderId, reason);
        return sagaOrchestrator.publishFailedEvent(
                        new OrderFailedEvent(orderId, correlationId, UUID.randomUUID().toString(), "timeout", reason))
                .then(Mono.just(fallbackOrder(orderId)));
    }

    private Order fallbackOrder(Long orderId) {
        log.warn("Returning fallback order for {}", orderId);
        return new Order(orderId, "failed", "unknown");
    }
}