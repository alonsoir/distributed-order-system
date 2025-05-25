package com.example.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("unit")
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
    @DisplayName("Verifica valores específicos del enum")
    void testSpecificEnumValues() {
        assertEquals("ORDER_CREATED", OrderStatus.ORDER_CREATED.getValue());
        assertEquals("ORDER_PENDING", OrderStatus.ORDER_PENDING.getValue());
        assertEquals("ORDER_COMPLETED", OrderStatus.ORDER_COMPLETED.getValue());
        assertEquals("ORDER_FAILED", OrderStatus.ORDER_FAILED.getValue());
        assertEquals("STOCK_RESERVED", OrderStatus.STOCK_RESERVED.getValue());
    }

    @Test
    @DisplayName("Verifica casos edge del fromValue")
    void testFromValueEdgeCases() {
        // Null debería lanzar excepción
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.fromValue(null));

        // Caso sensitivo
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.fromValue("order_created"));
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.fromValue("Order_Created"));
    }
}