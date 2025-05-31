package com.example.order.service.integration;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.repository.EventRepository;
import com.example.order.service.SagaOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("integration-test") // Changed from "integration" to "integration-test"
class OrderStatusConsistencyTest {

    @Autowired
    private SagaOrchestrator sagaOrchestrator;

    @Mock
    private EventRepository eventRepository;

    @Test
    @DisplayName("Verifica consistencia en manejo de estados entre string y enum")
    void testOrderStatusConsistency() {
        Long orderId = 123L;
        String correlationId = "test-corr";

        // Configurar respuesta del repositorio para capturar los argumentos
        ArgumentCaptor<OrderStatus> statusCaptor = ArgumentCaptor.forClass(OrderStatus.class);

        // Mock de findOrderById para retornar una orden con estado PENDING
        when(eventRepository.findOrderById(anyLong()))
                .thenReturn(Mono.just(new Order(orderId, OrderStatus.ORDER_PENDING, correlationId)));

        // Mock de updateOrderStatus para capturar el argumento de estado
        when(eventRepository.updateOrderStatus(anyLong(), statusCaptor.capture(), anyString()))
                .thenAnswer(invocation -> {
                    String status = invocation.getArgument(1);
                    return Mono.just(new Order(orderId, OrderStatus.fromValue(status), correlationId));
                });

        // Llamar al método que actualiza el estado
        // Ejemplo: supongamos que tenemos un método que actualiza de PENDING a COMPLETED
        Mono<Order> result = sagaOrchestrator.executeOrderSaga(5, 100.0);

        // Verificar el flujo
        StepVerifier.create(result)
                .expectNextMatches(order -> order.status() == OrderStatus.ORDER_COMPLETED)
                .verifyComplete();

        // Verificar que el estado pasado como string corresponde al enum correcto
        OrderStatus capturedStatus = statusCaptor.getValue();
        assertEquals(OrderStatus.ORDER_COMPLETED, capturedStatus,
                "El estado string debe corresponder al valor del enum OrderStatus.COMPLETED");
    }
}