package com.example.order.service;

import com.example.order.domain.OrderStateMachine;
import com.example.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("unit")
class OrderStateMachineServiceTest {

    private OrderStateMachineService service;
    private final Long TEST_ORDER_ID = 1234L;

    @BeforeEach
    void setUp() {
        service = new OrderStateMachineService();
    }

    @Test
    @DisplayName("Verifica validación de transiciones usando servicio")
    void testIsValidTransition() {
        assertTrue(service.isValidTransition(OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_CREATED));
        assertTrue(service.isValidTransition(OrderStatus.ORDER_CREATED, OrderStatus.ORDER_PROCESSING));
        assertFalse(service.isValidTransition(OrderStatus.ORDER_COMPLETED, OrderStatus.ORDER_FAILED));
    }

    @Test
    @DisplayName("Verifica obtención de estados válidos siguientes")
    void testGetValidNextStates() {
        Set<OrderStatus> nextStates = service.getValidNextStates(OrderStatus.ORDER_UNKNOWN);
        assertTrue(nextStates.contains(OrderStatus.ORDER_CREATED));
        assertTrue(nextStates.contains(OrderStatus.ORDER_FAILED));

        Set<OrderStatus> terminalStates = service.getValidNextStates(OrderStatus.ORDER_COMPLETED);
        assertTrue(terminalStates.isEmpty());
    }

    @Test
    @DisplayName("Verifica obtención de topics para transiciones")
    void testGetTopicForTransition() {
        String topic = service.getTopicForTransition(OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_CREATED);
        assertEquals("order-created", topic);

        String nullTopic = service.getTopicForTransition(OrderStatus.ORDER_CREATED, OrderStatus.ORDER_VALIDATED);
        assertNull(nullTopic);
    }

    @Test
    @DisplayName("Verifica verificación de estados terminales")
    void testIsTerminalState() {
        assertTrue(service.isTerminalState(OrderStatus.ORDER_COMPLETED));
        assertTrue(service.isTerminalState(OrderStatus.ORDER_FAILED));
        assertFalse(service.isTerminalState(OrderStatus.ORDER_PROCESSING));
    }

    @Test
    @DisplayName("Verifica creación de máquinas de estado para órdenes")
    void testCreateForOrder() {
        OrderStateMachine stateMachine = service.createForOrder(TEST_ORDER_ID, OrderStatus.ORDER_UNKNOWN);

        assertNotNull(stateMachine);
        assertTrue(stateMachine.isStateful());
        assertEquals(TEST_ORDER_ID, stateMachine.getOrderId());
        assertEquals(OrderStatus.ORDER_UNKNOWN, stateMachine.getCurrentStatus());
    }

    @Test
    @DisplayName("Verifica validaciones en creación de máquinas")
    void testCreateForOrderValidations() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createForOrder(null, OrderStatus.ORDER_UNKNOWN));
        assertThrows(IllegalArgumentException.class,
                () -> service.createForOrder(TEST_ORDER_ID, null));
    }

    @Test
    @DisplayName("Verifica obtención y remoción de máquinas")
    void testGetAndRemoveForOrder() {
        // Crear máquina
        OrderStateMachine created = service.createForOrder(TEST_ORDER_ID, OrderStatus.ORDER_UNKNOWN);

        // Obtener máquina existente
        OrderStateMachine retrieved = service.getForOrder(TEST_ORDER_ID);
        assertEquals(created, retrieved);

        // Remover máquina
        service.removeForOrder(TEST_ORDER_ID);
        assertNull(service.getForOrder(TEST_ORDER_ID));

        // Verificar contador
        assertEquals(0, service.getActiveStateMachineCount());
    }

    @Test
    @DisplayName("Verifica obtención o creación de máquinas")
    void testGetOrCreateForOrder() {
        // Primera llamada: crea nueva
        OrderStateMachine first = service.getOrCreateForOrder(TEST_ORDER_ID, OrderStatus.ORDER_UNKNOWN);
        assertEquals(1, service.getActiveStateMachineCount());

        // Segunda llamada: retorna existente
        OrderStateMachine second = service.getOrCreateForOrder(TEST_ORDER_ID, OrderStatus.ORDER_CREATED);
        assertEquals(first, second);
        assertEquals(1, service.getActiveStateMachineCount());
    }

    @Test
    @DisplayName("Verifica métodos de utilidad")
    void testUtilityMethods() {
        assertEquals(0, service.calculateDistanceBetweenStates(
                OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_UNKNOWN));

        OrderStatus bestNext = service.getBestNextStateTowards(
                OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_CREATED);
        assertEquals(OrderStatus.ORDER_CREATED, bestNext);
    }

    @Test
    @DisplayName("Verifica cleanup de estados terminales")
    void testCleanupTerminalStates() {
        // Crear máquinas en diferentes estados
        service.createForOrder(1L, OrderStatus.ORDER_PROCESSING);
        service.createForOrder(2L, OrderStatus.ORDER_COMPLETED);  // Terminal
        service.createForOrder(3L, OrderStatus.ORDER_FAILED);     // Terminal

        assertEquals(3, service.getActiveStateMachineCount());

        // Cleanup debería remover solo las terminales
        service.cleanupTerminalStates();
        assertEquals(1, service.getActiveStateMachineCount());

        // Solo debería quedar la máquina no terminal
        assertNotNull(service.getForOrder(1L));
        assertNull(service.getForOrder(2L));
        assertNull(service.getForOrder(3L));
    }
}
