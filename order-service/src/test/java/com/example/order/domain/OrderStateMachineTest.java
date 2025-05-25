package com.example.order.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("unit")
class OrderStateMachineTest {

    private OrderStateMachine statelessMachine;
    private OrderStateMachine statefulMachine;
    private final Long TEST_ORDER_ID = 1234L;

    @BeforeEach
    void setUp() {
        statelessMachine = new OrderStateMachine();
        statefulMachine = new OrderStateMachine(TEST_ORDER_ID, OrderStatus.ORDER_UNKNOWN);
    }

    @Test
    @DisplayName("Verifica transiciones válidas usando máquina sin estado")
    void testValidTransitions_Stateless() {
        // Transiciones válidas desde ORDER_UNKNOWN
        assertTrue(statelessMachine.isValidTransition(OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_CREATED));
        assertTrue(statelessMachine.isValidTransition(OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_FAILED));
        assertTrue(statelessMachine.isValidTransition(OrderStatus.ORDER_UNKNOWN, OrderStatus.TECHNICAL_EXCEPTION));

        // Transiciones válidas desde ORDER_CREATED
        assertTrue(statelessMachine.isValidTransition(OrderStatus.ORDER_CREATED, OrderStatus.ORDER_PROCESSING));
        assertTrue(statelessMachine.isValidTransition(OrderStatus.ORDER_CREATED, OrderStatus.PAYMENT_PENDING));

        // Estados terminales no tienen transiciones válidas
        assertFalse(statelessMachine.isValidTransition(OrderStatus.ORDER_COMPLETED, OrderStatus.ORDER_FAILED));
        assertFalse(statelessMachine.isValidTransition(OrderStatus.ORDER_FAILED, OrderStatus.ORDER_COMPLETED));
    }

    @Test
    @DisplayName("Verifica estados siguientes válidos")
    void testGetValidNextStates() {
        Set<OrderStatus> nextFromUnknown = statelessMachine.getValidNextStates(OrderStatus.ORDER_UNKNOWN);
        assertTrue(nextFromUnknown.contains(OrderStatus.ORDER_CREATED));
        assertTrue(nextFromUnknown.contains(OrderStatus.ORDER_FAILED));
        assertTrue(nextFromUnknown.contains(OrderStatus.TECHNICAL_EXCEPTION));

        // Estados terminales no tienen siguientes
        Set<OrderStatus> nextFromCompleted = statelessMachine.getValidNextStates(OrderStatus.ORDER_COMPLETED);
        assertTrue(nextFromCompleted.isEmpty());
    }

    @Test
    @DisplayName("Verifica mapeo de topics para transiciones")
    void testTopicMapping() {
        String topic = statelessMachine.getTopicNameForTransition(
                OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_CREATED);
        assertEquals("order-created", topic);

        String completedTopic = statelessMachine.getTopicNameForTransition(
                OrderStatus.STOCK_RESERVED, OrderStatus.ORDER_COMPLETED);
        assertEquals("order-completed", completedTopic); // ✅ Usar completedTopic, no topic

        // Transición sin mapeo específico
        String unmappedTopic = statelessMachine.getTopicNameForTransition(
                OrderStatus.ORDER_CREATED, OrderStatus.ORDER_VALIDATED);
        assertNull(unmappedTopic);
    }

    @Test
    @DisplayName("Verifica estados terminales")
    void testTerminalStates() {
        assertTrue(statelessMachine.isTerminalState(OrderStatus.ORDER_COMPLETED));
        assertTrue(statelessMachine.isTerminalState(OrderStatus.ORDER_FAILED));
        assertTrue(statelessMachine.isTerminalState(OrderStatus.ORDER_CANCELED));
        assertTrue(statelessMachine.isTerminalState(OrderStatus.ORDER_ABORTED));

        assertFalse(statelessMachine.isTerminalState(OrderStatus.ORDER_UNKNOWN));
        assertFalse(statelessMachine.isTerminalState(OrderStatus.ORDER_PROCESSING));
    }

    @Test
    @DisplayName("Verifica funcionalidad de máquina con estado")
    void testStatefulMachine() {
        assertTrue(statefulMachine.isStateful());
        assertEquals(TEST_ORDER_ID, statefulMachine.getOrderId());
        assertEquals(OrderStatus.ORDER_UNKNOWN, statefulMachine.getCurrentStatus());

        // Puede transicionar a ORDER_CREATED
        assertTrue(statefulMachine.canTransitionTo(OrderStatus.ORDER_CREATED));

        // Ejecutar transición
        statefulMachine.transitionTo(OrderStatus.ORDER_CREATED);
        assertEquals(OrderStatus.ORDER_CREATED, statefulMachine.getCurrentStatus());

        // No puede transicionar a estados inválidos
        assertFalse(statefulMachine.canTransitionTo(OrderStatus.ORDER_COMPLETED));
        assertThrows(IllegalStateException.class,
                () -> statefulMachine.transitionTo(OrderStatus.ORDER_COMPLETED));
    }

    @Test
    @DisplayName("Verifica validaciones de máquina sin estado")
    void testStatelessMachineValidations() {
        assertFalse(statelessMachine.isStateful());

        assertThrows(IllegalStateException.class, () -> statelessMachine.getOrderId());
        assertThrows(IllegalStateException.class, () -> statelessMachine.getCurrentStatus());
        assertThrows(IllegalStateException.class, () -> statelessMachine.canTransitionTo(OrderStatus.ORDER_CREATED));
        assertThrows(IllegalStateException.class, () -> statelessMachine.transitionTo(OrderStatus.ORDER_CREATED));
    }

    @Test
    @DisplayName("Verifica construcción con parámetros nulos")
    void testConstructorValidations() {
        assertThrows(IllegalArgumentException.class, () -> new OrderStateMachine(null, OrderStatus.ORDER_UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> new OrderStateMachine(TEST_ORDER_ID, null));
    }

    @Test
    @DisplayName("Verifica cálculo de distancia entre estados")
    void testCalculateDistanceBetweenStates() {
        // Distancia cero (mismo estado)
        assertEquals(0, statelessMachine.calculateDistanceBetweenStates(
                OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_UNKNOWN));

        // Distancia uno (transición directa)
        assertEquals(1, statelessMachine.calculateDistanceBetweenStates(
                OrderStatus.ORDER_UNKNOWN, OrderStatus.ORDER_CREATED));

        // Sin ruta posible (hacia estado terminal desde otro terminal)
        assertEquals(-1, statelessMachine.calculateDistanceBetweenStates(
                OrderStatus.ORDER_COMPLETED, OrderStatus.ORDER_FAILED));
    }
}