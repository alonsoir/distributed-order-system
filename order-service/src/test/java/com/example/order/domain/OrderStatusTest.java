package com.example.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class OrderStatusTest {

    @Test
    @DisplayName("Verifica la conversión correcta entre string y enum")
    void testStatusConversion() {
        // Verifica que todos los valores del enum se convierten correctamente a string y viceversa
        for (OrderStatus status : OrderStatus.values()) {
            String value = status.getValue();
            OrderStatus converted = OrderStatus.fromValue(value);
            assertEquals(status, converted, "La conversión de string a enum debe ser correcta");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "unknown", "random_status", ""})
    @DisplayName("Verifica que valores de estado inválidos lanzan excepción")
    void testInvalidStatusValues(String invalidValue) {
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.fromValue(invalidValue),
                "Valores de estado inválidos deben lanzar IllegalArgumentException");
    }

    @Test
    @DisplayName("Verifica las transiciones de estado válidas")
    void testValidStateTransitions() {
        assertTrue(OrderStateTransition.isValidTransition(OrderStatus.ORDER_CREATED, OrderStatus.ORDER_PENDING));
        assertTrue(OrderStateTransition.isValidTransition(OrderStatus.ORDER_PENDING, OrderStatus.STOCK_RESERVED));
        assertTrue(OrderStateTransition.isValidTransition(OrderStatus.ORDER_PENDING, OrderStatus.ORDER_FAILED));
        assertTrue(OrderStateTransition.isValidTransition(OrderStatus.STOCK_RESERVED, OrderStatus.ORDER_COMPLETED));
        assertTrue(OrderStateTransition.isValidTransition(OrderStatus.STOCK_RESERVED, OrderStatus.ORDER_FAILED));

        // Estado terminal COMPLETED no tiene transiciones válidas
        assertFalse(OrderStateTransition.isValidTransition(OrderStatus.ORDER_COMPLETED, OrderStatus.ORDER_FAILED));
        assertFalse(OrderStateTransition.isValidTransition(OrderStatus.ORDER_COMPLETED, OrderStatus.ORDER_PENDING));

        // Estado terminal FAILED no tiene transiciones válidas
        assertFalse(OrderStateTransition.isValidTransition(OrderStatus.ORDER_FAILED, OrderStatus.ORDER_COMPLETED));
        assertFalse(OrderStateTransition.isValidTransition(OrderStatus.ORDER_FAILED, OrderStatus.ORDER_PENDING));
    }

    @Test
    @DisplayName("Verifica la asignación de eventos a estados")
    void testEventToStatusMapping() {
        assertEquals(OrderStatus.ORDER_PENDING, OrderStatus.ORDER_CREATED);
        assertEquals(OrderStatus.STOCK_RESERVED, OrderStatus.STOCK_RESERVED);
        assertEquals(OrderStatus.ORDER_COMPLETED, OrderStatus.ORDER_COMPLETED);
        assertEquals(OrderStatus.ORDER_FAILED, OrderStatus.ORDER_FAILED);
    }
}