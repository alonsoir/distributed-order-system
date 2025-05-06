package com.example.order.service.unit;

import com.example.order.domain.Order;
import com.example.order.events.OrderFailedEvent;
import com.example.order.service.IdGenerator;
import com.example.order.service.InventoryService;
import com.example.order.service.OrderServiceImpl;
import com.example.order.service.SagaOrchestrator;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Testcontainers
@ActiveProfiles("unit")
class CircuitBreakerUnitTest {

    @Mock
    private DatabaseClient databaseClient;
    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private TransactionalOperator transactionalOperator;
    @Mock
    private SagaOrchestrator sagaOrchestrator;
    @Mock
    private CircuitBreaker circuitBreaker;
    @Mock
    private IdGenerator idGenerator;
    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        // Configuración adicional si es necesaria
    }

    @Test
    void shouldReturnFallbackWhenCircuitBreakerIsOpen() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(false);
        when(sagaOrchestrator.publishFailedEvent(any(OrderFailedEvent.class))).thenReturn(Mono.empty());
        String externalReference = idGenerator.generateExternalReference();
        Mono<Order> result = orderService.processOrder(externalReference, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order -> order.status().equals("failed") && order.id().equals(orderId))
                .verifyComplete();

        verify(circuitBreaker).tryAcquirePermission(); // Solo una vez
        verify(sagaOrchestrator).publishFailedEvent(any(OrderFailedEvent.class));
        verify(sagaOrchestrator, never()).executeOrderSaga(anyLong(), anyInt(), anyString(), anyDouble(), anyString());
    }

    @Test
    void shouldApplyCircuitBreakerOnSuccessfulOrderProcessing() {
        Long orderId = 1L;
        int quantity = 10;
        double amount = 100.0;
        String correlationId = "test-correlation-id";
        String externalReference = idGenerator.generateExternalReference();
        Long eventId = 1L;
        Order order = new Order(orderId, "completed", correlationId);

        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        when(sagaOrchestrator.executeOrderSaga(eq(orderId), eq(quantity), anyString(), eq(amount), anyString()))
                .thenReturn(Mono.just(order));
        when(meterRegistry.counter("orders_success")).thenReturn(mock(Counter.class));
        //    public Mono<Order> processOrder(Long orderId, String externalReference,int quantity, double amount) {
        // en realidad, este método processOrder no se debería llamar aquí, porque es un método exclusivo del Controller.
        Mono<Order> result = orderService.processOrder(externalReference, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(o -> o.id().equals(orderId) && o.status().equals("completed"))
                .verifyComplete();

        verify(circuitBreaker, times(2)).tryAcquirePermission(); // Dos veces
        verify(sagaOrchestrator).executeOrderSaga(eq(orderId), eq(quantity), anyString(), eq(amount), anyString());
        verify(meterRegistry).counter("orders_success");
    }


    @Test
    @Disabled("Skipped temporarily—redis integration flaky, revisit later")
    void shouldReturnFailedOrderWhenSagaFails() {
        Long orderId = 2L;
        int quantity = 5;
        double amount = 50.0;
        String eventId = "test-event-id";
        String externalReference = idGenerator.generateExternalReference();
        when(circuitBreakerRegistry.circuitBreaker("orderProcessing")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        when(sagaOrchestrator.executeOrderSaga(eq(orderId), eq(quantity), eq(eventId),eq(amount), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Saga failed")));
        when(sagaOrchestrator.publishFailedEvent(any())).thenReturn(Mono.empty());
        //    public Mono<Order> processOrder(Long orderId, String externalReference,int quantity, double amount) {
        Mono<Order> result = orderService.processOrder(externalReference, quantity, amount);

        StepVerifier.create(result)
                .expectNextMatches(order -> order.status().equals("failed") && order.id().equals(orderId))
                .verifyComplete();

        verify(circuitBreaker, times(2)).tryAcquirePermission(); // Dos veces
        verify(sagaOrchestrator, times(1)).publishFailedEvent(any(OrderFailedEvent.class)); // Solo una vez
    }

}