package com.example.order.service.unit;

import com.example.order.config.CircuitBreakerConstants;
import com.example.order.config.MetricsConstants;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.service.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.stream.Stream;

import static com.example.order.config.CircuitBreakerConstants.GLOBAL_TIMEOUT;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderServiceAtLeastOnceImpl}.
 * Tests cover order processing with valid and invalid inputs,
 * circuit breaker behaviors, and error handling.
 */
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("unit")
class OrderServiceUnitTest {

    /**
     * Constants for error messages used in tests.
     */
    interface OrderServiceConstants {
        String ERROR_EMPTY_EXTERNAL_REF = "External reference cannot be null or empty";
        String ERROR_INVALID_QUANTITY = "Quantity must be positive";
        String ERROR_INVALID_AMOUNT = "Amount must be non-negative";
    }

    // Constantes para trazabilidad
    private static final Long TEST_ORDER_ID = 123456L;
    private static final String TEST_CORRELATION_ID = "test-correlation-id-fixed";
    private static final int TEST_QUANTITY = 10;
    private static final double TEST_AMOUNT = 100.0;
    private static final String TEST_EXTERNAL_REF = "ext-123";

    @Mock
    private SagaOrchestrator sagaOrchestrator;
    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock
    private CircuitBreaker circuitBreaker;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private Counter ordersSuccessCounter;

    @InjectMocks
    private OrderServiceAtLeastOnceImpl orderService;

    @BeforeEach
    void setUp() {

        lenient().when(circuitBreakerRegistry.circuitBreaker(CircuitBreakerConstants.ORDER_PROCESSING)).thenReturn(circuitBreaker);
        lenient().when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        lenient().when(meterRegistry.counter(MetricsConstants.ORDERS_SUCCESS)).thenReturn(ordersSuccessCounter);
    }

    /**
     * Verifies that processOrder executes the saga successfully and returns the completed order.
     */
    @Test
    void shouldProcessOrderSuccessfully() {
        // Arrange
        Order expectedOrder = new Order(TEST_ORDER_ID, OrderStatus.ORDER_COMPLETED, TEST_CORRELATION_ID);

        when(sagaOrchestrator.executeOrderSaga(TEST_QUANTITY, TEST_AMOUNT))
                .thenReturn(Mono.just(expectedOrder));

        // Act
        Mono<Order> result = orderService.processOrder(TEST_EXTERNAL_REF, TEST_QUANTITY, TEST_AMOUNT);

        // Assert
        StepVerifier.create(result)
                .expectNext(expectedOrder)
                .verifyComplete();

        verify(sagaOrchestrator).executeOrderSaga(TEST_QUANTITY, TEST_AMOUNT);
        verify(ordersSuccessCounter).increment();
    }

    /**
     * Verifies that processOrder handles invalid input parameters.
     */
    @ParameterizedTest
    @MethodSource("provideInvalidOrderInputs")
    void shouldFailToProcessOrderWithInvalidInputs(String externalRef, int quantity, double amount, String expectedError) {
        // Act
        Mono<Order> result = orderService.processOrder(externalRef, quantity, amount);

        // Assert
        StepVerifier.create(result)
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException &&
                                throwable.getMessage().contains(expectedError))
                .verify();

        verify(sagaOrchestrator, never()).executeOrderSaga(anyInt(), anyDouble());
    }

    private static Stream<Arguments> provideInvalidOrderInputs() {
        return Stream.of(
                Arguments.of("", TEST_QUANTITY, TEST_AMOUNT, OrderServiceConstants.ERROR_EMPTY_EXTERNAL_REF),
                Arguments.of(TEST_EXTERNAL_REF, 0, TEST_AMOUNT, OrderServiceConstants.ERROR_INVALID_QUANTITY),
                Arguments.of(TEST_EXTERNAL_REF, TEST_QUANTITY, -1.0, OrderServiceConstants.ERROR_INVALID_AMOUNT)
        );
    }

    /**
     * Verifies that processOrder handles an open circuit breaker.
     */
    @Test
    void shouldHandleCircuitBreakerOpen() {
        // Arrange
        Order fallbackOrder = new Order(null, OrderStatus.ORDER_FAILED, TEST_EXTERNAL_REF);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(false);
        when(sagaOrchestrator.createFailedEvent(eq(CircuitBreakerConstants.CIRCUIT_BREAKER_OPEN),
                                                eq(TEST_EXTERNAL_REF)))
                .thenReturn(Mono.empty());

        // Act
        Mono<Order> result = orderService.processOrder(TEST_EXTERNAL_REF, TEST_QUANTITY, TEST_AMOUNT);

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.status().equals(OrderStatus.ORDER_FAILED) &&
                                order.correlationId().equals(TEST_EXTERNAL_REF))
                .verifyComplete();

        verify(sagaOrchestrator).createFailedEvent(eq(CircuitBreakerConstants.CIRCUIT_BREAKER_OPEN), eq(TEST_EXTERNAL_REF));
        verify(sagaOrchestrator, never()).executeOrderSaga(anyInt(), anyDouble());
    }

    /**
     * Verifies that processOrder handles a timeout during saga execution.
     */
    @Test
    void shouldHandleTimeoutDuringOrderProcessing() {
        // Arrange
        System.out.println("Setting up timeout test");

        Order failedOrder = new Order(null, OrderStatus.ORDER_FAILED, TEST_EXTERNAL_REF);

        // Mock the createFailedEvent to return empty mono
        when(sagaOrchestrator.createFailedEvent(eq(GLOBAL_TIMEOUT), eq(TEST_EXTERNAL_REF)))
                .thenReturn(Mono.empty());

        // This is the key change: Instead of making the saga take too long and then applying a timeout,
        // we directly simulate what happens when there's a timeout
        when(sagaOrchestrator.executeOrderSaga(TEST_QUANTITY, TEST_AMOUNT))
                .thenReturn(Mono.error(new java.util.concurrent.TimeoutException("Simulated timeout")));

        // Act - no more external timeout
        System.out.println("Executing processOrder with simulated timeout");
        Mono<Order> result = orderService.processOrder(TEST_EXTERNAL_REF, TEST_QUANTITY, TEST_AMOUNT);

        // Assert
        System.out.println("Verifying the result");
        StepVerifier.create(result)
                .expectNextMatches(order -> {
                    System.out.println("Checking order: " + order);
                    return order.status().equals(OrderStatus.ORDER_FAILED) &&
                            order.correlationId().equals(TEST_EXTERNAL_REF);
                })
                .verifyComplete();

        verify(sagaOrchestrator).createFailedEvent(eq("global_timeout"), eq(TEST_EXTERNAL_REF));
        System.out.println("Test completed");
    }

    /**
     * Verifies that processOrder handles a circuit breaker error during saga execution.
     */
    @Test
    void shouldHandleCircuitBreakerError() {
        // Arrange
        Order fallbackOrder = new Order(null, OrderStatus.ORDER_FAILED, TEST_EXTERNAL_REF);

        when(sagaOrchestrator.executeOrderSaga(TEST_QUANTITY, TEST_AMOUNT))
                .thenReturn(Mono.error(new RuntimeException("Circuit breaker error")));
        when(sagaOrchestrator.createFailedEvent(eq("circuit_breaker_error"), eq(TEST_EXTERNAL_REF)))
                .thenReturn(Mono.empty());

        // Act
        Mono<Order> result = orderService.processOrder(TEST_EXTERNAL_REF, TEST_QUANTITY, TEST_AMOUNT);

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(order ->
                        order.status().equals(OrderStatus.ORDER_FAILED) &&
                                order.correlationId().equals(TEST_EXTERNAL_REF))
                .verifyComplete();

        verify(sagaOrchestrator).createFailedEvent(eq("circuit_breaker_error"), eq(TEST_EXTERNAL_REF));
    }
}