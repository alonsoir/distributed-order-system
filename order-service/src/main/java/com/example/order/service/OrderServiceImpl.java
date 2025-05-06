package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.model.SagaStep;
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
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final SagaOrchestrator sagaOrchestrator;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry meterRegistry;
    private final IdGenerator idGenerator;

    @Override
    public Mono<Order> processOrder(String externalReference,int quantity, double amount) {

        String correlationId = idGenerator.generateCorrelationId();

        String eventId = idGenerator.generateEventId();
        log.info("Starting order with correlationId {} eventId {} quantity {} amount {}",
                 correlationId, eventId, quantity, amount);

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("orderProcessing");

        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("Circuit breaker open for order with eventId", eventId);
            return onTimeout(correlationId, eventId, "processOrder", "circuit_breaker_open");
        }

        Mono<Order> orderMono = sagaOrchestrator.executeOrderSaga(quantity, eventId, amount, correlationId)
                .timeout(Duration.ofSeconds(15))
                .doOnError(e -> log.error("Timeout or error in order saga for order {}: {}", orderId, e.getMessage()))
                .onErrorResume(e -> onTimeout(orderId, correlationId, eventId, "processOrder", "global_timeout")); // actualizado

        return orderMono.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .doOnSuccess(order -> {
                    log.info("Order {} processed successfully: {}", orderId, order);
                    meterRegistry.counter("orders_success").increment();
                })
                .onErrorResume(e -> onTimeout(orderId, correlationId, eventId, "processOrder", "circuit_breaker_open")); // actualizado
    }


    @Override
    public Mono<Order> createOrder(Long orderId, String correlationId) {
        log.info("Creating order {} with correlationId {}", orderId, correlationId);
        return sagaOrchestrator.createOrder(orderId, correlationId);
    }

    @Override
    public Mono<Void> publishEvent(OrderEvent event) {
        log.info("Publishing event: {}", event);
        return sagaOrchestrator.publishFailedEvent(event instanceof OrderFailedEvent failed
                ? failed
                : new OrderFailedEvent(event.getOrderId(), event.getCorrelationId(), event.getEventId(), "unknown", "wrapped in publishEvent"));
    }

    @Override
    public Mono<OrderEvent> executeStep(SagaStep step) {
        log.info("Delegating execution of step {} to SagaOrchestrator", step.getName());
        return sagaOrchestrator.executeStep(step);
    }

    private Mono<Order> onTimeout(String correlationId, String eventId,String step,String reason) {
        log.warn("Returning fallback order with correlationId {} with reason: {}", correlationId, reason);
        OrderFailedEvent event = new OrderFailedEvent(correlationId, eventId,step,reason);
        return sagaOrchestrator.publishFailedEvent(event)
                .then(Mono.fromCallable(() -> {
                    Order orderFallBack = fallbackOrder(orderId);
                    log.info("Fallback order created for {}: {}", orderId, orderFallBack);
                    return orderFallBack;
                }));
    }


    private Order fallbackOrder(Long orderId) {
        log.warn("Returning fallback order for {}", orderId);
        return new Order(orderId, "failed", "unknown");
    }
}
