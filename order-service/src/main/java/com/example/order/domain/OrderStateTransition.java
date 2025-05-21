package com.example.order.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * Clase que gestiona las transiciones de estado para órdenes
 */
public class OrderStateTransition {

    // Mapa para definir las transiciones válidas entre estados
    private static final Map<OrderStatus, OrderStatus[]> VALID_TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        // Definir transiciones válidas. Están todas? yo creo que no.
        // Todos los estados están definidos en OrderStatus.
        // HAY QUE DEFINIR cuales son los estados válidos, las transciones válidas y cuales no.
        // Luego, este mapa debe estar a disposicion del SagaOrchestrator, tanto del AT LEAST ONCE
        // como del AT MOST ONCE.
        VALID_TRANSITIONS.put(OrderStatus.ORDER_CREATED, new OrderStatus[]{OrderStatus.ORDER_PENDING});
        VALID_TRANSITIONS.put(OrderStatus.ORDER_PENDING, new OrderStatus[]{OrderStatus.STOCK_RESERVED, OrderStatus.ORDER_FAILED});
        VALID_TRANSITIONS.put(OrderStatus.STOCK_RESERVED, new OrderStatus[]{OrderStatus.ORDER_COMPLETED, OrderStatus.ORDER_FAILED});
        VALID_TRANSITIONS.put(OrderStatus.ORDER_COMPLETED, new OrderStatus[]{});
        VALID_TRANSITIONS.put(OrderStatus.ORDER_FAILED, new OrderStatus[]{});
    }

    /**
     * Valida si una transición de estado es permitida
     * @param currentStatus Estado actual de la orden
     * @param newStatus Estado al que se quiere transicionar
     * @return true si la transición es válida, false en caso contrario
     */
    public static boolean isValidTransition(OrderStatus currentStatus, OrderStatus newStatus) {
        if (currentStatus == null || newStatus == null) {
            return false;
        }

        OrderStatus[] validNextStates = VALID_TRANSITIONS.get(currentStatus);
        if (validNextStates == null) {
            return false;
        }

        for (OrderStatus state : validNextStates) {
            if (state == newStatus) {
                return true;
            }
        }
        return false;
    }

    /**
     * Obtiene el siguiente estado permitido basado en el estado actual
     * @param currentStatus Estado actual de la orden
     * @return Array de estados válidos para la siguiente transición
     */
    public static OrderStatus[] getValidNextStates(OrderStatus currentStatus) {
        if (currentStatus == null) {
            return new OrderStatus[0];
        }
        OrderStatus[] validStates = VALID_TRANSITIONS.get(currentStatus);
        return validStates != null ? validStates : new OrderStatus[0];
    }

    /**
     * Valida la transición y lanza una excepción si no es válida
     * @param currentStatus Estado actual de la orden
     * @param newStatus Estado al que se quiere transicionar
     * @throws IllegalStateException si la transición no es válida
     */
    public static void validateTransition(OrderStatus currentStatus, OrderStatus newStatus) {
        if (!isValidTransition(currentStatus, newStatus)) {
            throw new IllegalStateException(
                    "Invalid state transition from " + currentStatus + " to " + newStatus);
        }
    }
}