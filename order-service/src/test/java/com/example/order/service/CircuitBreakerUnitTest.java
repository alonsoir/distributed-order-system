package com.example.order.service;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerUnitTest {

    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Mock
    private CircuitBreaker circuitBreaker;

    @InjectMocks
    private OrderService orderService;

    @Test
    void shouldApplyCircuitBreakerOnOrderProcessing() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;
        String correlationId = "corr-123";

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(orderService.createOrder(orderId, correlationId)).thenReturn(Mono.just(new Order(orderId, "pending", correlationId)));

        StepVerifier.create(orderService.processOrder(orderId, quantity, amount))
                .expectNextMatches(order -> order.status().equals("pending"))
                .verifyComplete();

        verify(circuitBreakerRegistry).circuitBreaker("orderProcessing");
    }
}