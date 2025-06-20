package com.example.order.domain;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;

@Slf4j
public class OrderStateMachine {

    /**
     * Thread-safe singleton holder using initialization-on-demand holder idiom.
     * This pattern ensures thread safety without synchronization overhead.
     */
    private static class SingletonHolder {
        private static final OrderStateMachine INSTANCE = new OrderStateMachine();
    }

    private final Map<OrderStatus, Set<OrderStatus>> transitions;
    private final Map<String, String> topicMappings;

    // Campos para instancias con estado específico
    private final Long orderId;
    private volatile OrderStatus currentStatus;

    /**
     * Constructor por defecto - para instancia estática (reglas)
     */
    public OrderStateMachine() {
        this.orderId = null;
        this.currentStatus = null;
        this.transitions = initializeTransitions();
        this.topicMappings = initializeTopicMappings();
    }

    /**
     * Constructor para instancias específicas de orden
     */
    public OrderStateMachine(Long orderId, OrderStatus initialStatus) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId cannot be null for stateful instances");
        }
        if (initialStatus == null) {
            throw new IllegalArgumentException("initialStatus cannot be null for stateful instances");
        }

        this.orderId = orderId;
        this.currentStatus = initialStatus;
        this.transitions = initializeTransitions();
        this.topicMappings = initializeTopicMappings();

        log.debug("Created stateful OrderStateMachine for order {} with status {}", orderId, initialStatus);
    }

    /**
     * Método para obtener instancia estática (solo reglas) - DEPRECATED
     * Thread-safe singleton usando initialization-on-demand holder idiom
     * @deprecated Use OrderStateMachineService instead
     */
    @Deprecated
    public static OrderStateMachine getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * Método alternativo para facilitar testing
     * Nota: Con el holder pattern, la instancia no se puede resetear.
     * Para testing, se recomienda usar OrderStateMachineService o crear instancias nuevas.
     */
    public static void resetInstance() {
        log.warn("resetInstance() is not supported with initialization-on-demand holder pattern. " +
                "Use OrderStateMachineService for testing or create new instances instead.");
    }

    /**
     * Verifica si esta es una instancia con estado (asociada a una orden específica)
     */
    public boolean isStateful() {
        return orderId != null;
    }

    /**
     * Obtiene el ID de la orden (solo para instancias con estado)
     */
    public Long getOrderId() {
        if (!isStateful()) {
            throw new IllegalStateException("This is a stateless instance, no order ID available");
        }
        return orderId;
    }

    /**
     * Obtiene el estado actual (solo para instancias con estado)
     */
    public OrderStatus getCurrentStatus() {
        if (!isStateful()) {
            throw new IllegalStateException("This is a stateless instance, no current status available");
        }
        return currentStatus;
    }

    /**
     * Verifica si puede hacer transición al estado objetivo (instancias con estado)
     */
    public boolean canTransitionTo(OrderStatus newStatus) {
        if (!isStateful()) {
            throw new IllegalStateException("This is a stateless instance, use isValidTransition instead");
        }
        return isValidTransition(currentStatus, newStatus);
    }

    /**
     * Realiza transición al nuevo estado (instancias con estado)
     */
    public synchronized void transitionTo(OrderStatus newStatus) {
        if (!isStateful()) {
            throw new IllegalStateException("This is a stateless instance, cannot transition");
        }

        if (!canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    String.format("Invalid transition from %s to %s for order %d",
                            currentStatus, newStatus, orderId));
        }

        OrderStatus previousStatus = currentStatus;
        this.currentStatus = newStatus;

        log.info("Order {} transitioned from {} to {}", orderId, previousStatus, newStatus);
    }

    /**
     * Obtiene el topic para una transición desde el estado actual (instancias con estado)
     */
    public String getTopicForTransitionTo(OrderStatus targetStatus) {
        if (!isStateful()) {
            throw new IllegalStateException("This is a stateless instance, use getTopicNameForTransition instead");
        }
        return getTopicNameForTransition(currentStatus, targetStatus);
    }

    /**
     * Obtiene los estados válidos siguientes desde el estado actual (instancias con estado)
     */
    public Set<OrderStatus> getValidNextStatesFromCurrent() {
        if (!isStateful()) {
            throw new IllegalStateException("This is a stateless instance, use getValidNextStates instead");
        }
        return getValidNextStates(currentStatus);
    }

    // ========== MÉTODOS ESTÁTICOS (SIN ESTADO) ==========

    /**
     * Verifica si una transición de estado es válida (método estático)
     */
    public boolean isValidTransition(OrderStatus from, OrderStatus to) {
        Set<OrderStatus> validNextStates = transitions.get(from);
        boolean isValid = validNextStates != null && validNextStates.contains(to);

        if (!isValid) {
            log.debug("Invalid transition attempted: {} -> {}", from, to);
        }

        return isValid;
    }

    /**
     * Obtiene los estados válidos siguientes para un estado dado (método estático)
     */
    public Set<OrderStatus> getValidNextStates(OrderStatus currentStatus) {
        Set<OrderStatus> validStates = transitions.getOrDefault(currentStatus, EnumSet.noneOf(OrderStatus.class));
        log.debug("Valid next states for {}: {}", currentStatus, validStates);
        return validStates;
    }

    /**
     * Obtiene el nombre del topic para una transición específica (método estático)
     */
    public String getTopicNameForTransition(OrderStatus from, OrderStatus to) {
        String key = from.name() + "->" + to.name();
        String topic = topicMappings.get(key);

        if (topic == null) {
            log.debug("No specific topic mapping found for transition {} -> {}", from, to);
        }

        return topic;
    }

    /**
     * Verifica si un estado es terminal (método estático)
     */
    public boolean isTerminalState(OrderStatus status) {
        Set<OrderStatus> nextStates = getValidNextStates(status);
        boolean isTerminal = nextStates.isEmpty();

        if (isTerminal) {
            log.debug("State {} is terminal", status);
        }

        return isTerminal;
    }

    /**
     * Obtiene el mejor estado siguiente hacia un objetivo específico (método estático)
     */
    public OrderStatus getBestNextStateTowards(OrderStatus currentStatus, OrderStatus targetStatus) {
        Set<OrderStatus> validNextStates = getValidNextStates(currentStatus);

        if (validNextStates.contains(targetStatus)) {
            return targetStatus;
        }

        // Estrategia de progresión hacia estados objetivo comunes
        if (targetStatus == OrderStatus.ORDER_COMPLETED) {
            // Priorizar estados que nos acerquen al completado
            if (validNextStates.contains(OrderStatus.ORDER_PROCESSING)) {
                return OrderStatus.ORDER_PROCESSING;
            }
            if (validNextStates.contains(OrderStatus.ORDER_PREPARED)) {
                return OrderStatus.ORDER_PREPARED;
            }
            if (validNextStates.contains(OrderStatus.SHIPPING_PENDING)) {
                return OrderStatus.SHIPPING_PENDING;
            }
            if (validNextStates.contains(OrderStatus.DELIVERED)) {
                return OrderStatus.DELIVERED;
            }
        }

        // Si no hay una progresión clara, retornar el primer estado válido o null
        return validNextStates.isEmpty() ? null : validNextStates.iterator().next();
    }

    /**
     * Calcula la distancia (número de pasos) entre dos estados (método estático)
     */
    public int calculateDistanceBetweenStates(OrderStatus from, OrderStatus to) {
        if (from == to) {
            return 0;
        }

        // Implementación simple usando BFS - puede ser optimizada
        Set<OrderStatus> visited = EnumSet.noneOf(OrderStatus.class);
        Map<OrderStatus, Integer> distances = new HashMap<>();

        distances.put(from, 0);
        visited.add(from);

        // BFS simple
        boolean found = false;
        int currentDistance = 0;
        Set<OrderStatus> currentLevel = EnumSet.of(from);

        while (!currentLevel.isEmpty() && !found && currentDistance < 10) { // Límite para evitar loops infinitos
            Set<OrderStatus> nextLevel = EnumSet.noneOf(OrderStatus.class);

            for (OrderStatus current : currentLevel) {
                Set<OrderStatus> neighbors = getValidNextStates(current);

                for (OrderStatus neighbor : neighbors) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        distances.put(neighbor, currentDistance + 1);
                        nextLevel.add(neighbor);

                        if (neighbor == to) {
                            found = true;
                        }
                    }
                }
            }

            currentLevel = nextLevel;
            currentDistance++;
        }

        return distances.getOrDefault(to, -1); // -1 indica que no hay ruta
    }

    // ========== MÉTODOS DE INICIALIZACIÓN (SIN CAMBIOS) ==========

    private Map<OrderStatus, Set<OrderStatus>> initializeTransitions() {
        Map<OrderStatus, Set<OrderStatus>> map = new HashMap<>();

        // Estados iniciales
        map.put(OrderStatus.ORDER_UNKNOWN, EnumSet.of(
                OrderStatus.ORDER_CREATED, OrderStatus.ORDER_FAILED, OrderStatus.TECHNICAL_EXCEPTION));

        map.put(OrderStatus.ORDER_CREATED, EnumSet.of(
                OrderStatus.ORDER_VALIDATED, OrderStatus.PAYMENT_PENDING, OrderStatus.ORDER_PROCESSING,
                OrderStatus.ORDER_FAILED, OrderStatus.TECHNICAL_EXCEPTION, OrderStatus.WAITING_RETRY));

        map.put(OrderStatus.ORDER_VALIDATED, EnumSet.of(
                OrderStatus.PAYMENT_PENDING, OrderStatus.ORDER_PROCESSING,
                OrderStatus.ORDER_FAILED, OrderStatus.TECHNICAL_EXCEPTION));

        // Estados de pago
        map.put(OrderStatus.PAYMENT_PENDING, EnumSet.of(
                OrderStatus.PAYMENT_PROCESSING, OrderStatus.PAYMENT_CONFIRMED, OrderStatus.PAYMENT_DECLINED));

        map.put(OrderStatus.PAYMENT_PROCESSING, EnumSet.of(
                OrderStatus.PAYMENT_CONFIRMED, OrderStatus.PAYMENT_DECLINED, OrderStatus.TECHNICAL_EXCEPTION));

        map.put(OrderStatus.PAYMENT_CONFIRMED, EnumSet.of(
                OrderStatus.STOCK_CHECKING, OrderStatus.STOCK_RESERVED, OrderStatus.ORDER_PROCESSING));

        map.put(OrderStatus.PAYMENT_DECLINED, EnumSet.of(
                OrderStatus.ORDER_FAILED, OrderStatus.ORDER_CANCELED));

        // Estados de inventario
        map.put(OrderStatus.STOCK_CHECKING, EnumSet.of(
                OrderStatus.STOCK_RESERVED, OrderStatus.STOCK_UNAVAILABLE, OrderStatus.TECHNICAL_EXCEPTION));

        map.put(OrderStatus.STOCK_RESERVED, EnumSet.of(
                OrderStatus.ORDER_PROCESSING, OrderStatus.ORDER_PREPARED, OrderStatus.SHIPPING_PENDING,
                OrderStatus.ORDER_COMPLETED)); // Permitir transición directa a completado

        map.put(OrderStatus.STOCK_UNAVAILABLE, EnumSet.of(
                OrderStatus.WAITING_RETRY, OrderStatus.ORDER_FAILED, OrderStatus.ORDER_CANCELED));

        // Estados de procesamiento
        map.put(OrderStatus.ORDER_PENDING, EnumSet.of(
                OrderStatus.ORDER_PROCESSING, OrderStatus.ORDER_FAILED, OrderStatus.TECHNICAL_EXCEPTION));

        map.put(OrderStatus.ORDER_PROCESSING, EnumSet.of(
                OrderStatus.ORDER_PREPARED, OrderStatus.SHIPPING_PENDING, OrderStatus.ORDER_COMPLETED,
                OrderStatus.TECHNICAL_EXCEPTION, OrderStatus.WAITING_RETRY));

        map.put(OrderStatus.ORDER_PREPARED, EnumSet.of(
                OrderStatus.SHIPPING_PENDING, OrderStatus.SHIPPING_ASSIGNED, OrderStatus.ORDER_COMPLETED));

        // Estados de envío
        map.put(OrderStatus.SHIPPING_PENDING, EnumSet.of(
                OrderStatus.SHIPPING_ASSIGNED, OrderStatus.SHIPPING_IN_PROGRESS, OrderStatus.ORDER_COMPLETED));

        map.put(OrderStatus.SHIPPING_ASSIGNED, EnumSet.of(
                OrderStatus.SHIPPING_IN_PROGRESS, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.SHIPPING_DELAYED));

        map.put(OrderStatus.SHIPPING_IN_PROGRESS, EnumSet.of(
                OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED_TO_COURIER, OrderStatus.SHIPPING_DELAYED,
                OrderStatus.SHIPPING_EXCEPTION));

        map.put(OrderStatus.SHIPPING_DELAYED, EnumSet.of(
                OrderStatus.SHIPPING_IN_PROGRESS, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.SHIPPING_EXCEPTION));

        map.put(OrderStatus.SHIPPING_EXCEPTION, EnumSet.of(
                OrderStatus.WAITING_RETRY, OrderStatus.MANUAL_REVIEW, OrderStatus.ORDER_FAILED));

        // Estados de entrega
        map.put(OrderStatus.DELIVERED_TO_COURIER, EnumSet.of(
                OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERY_ATTEMPTED, OrderStatus.DELIVERED));

        map.put(OrderStatus.OUT_FOR_DELIVERY, EnumSet.of(
                OrderStatus.DELIVERED, OrderStatus.DELIVERY_ATTEMPTED, OrderStatus.DELIVERY_EXCEPTION));

        map.put(OrderStatus.DELIVERED, EnumSet.of(
                OrderStatus.PENDING_CONFIRMATION, OrderStatus.RECEIVED_CONFIRMED, OrderStatus.ORDER_COMPLETED,
                OrderStatus.RETURN_REQUESTED));

        map.put(OrderStatus.DELIVERY_ATTEMPTED, EnumSet.of(
                OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERY_EXCEPTION, OrderStatus.WAITING_RETRY));

        map.put(OrderStatus.DELIVERY_EXCEPTION, EnumSet.of(
                OrderStatus.WAITING_RETRY, OrderStatus.MANUAL_REVIEW, OrderStatus.RETURN_REQUESTED));

        // Estados de confirmación
        map.put(OrderStatus.PENDING_CONFIRMATION, EnumSet.of(
                OrderStatus.RECEIVED_CONFIRMED, OrderStatus.RETURN_REQUESTED, OrderStatus.ORDER_COMPLETED));

        map.put(OrderStatus.RECEIVED_CONFIRMED, EnumSet.of(
                OrderStatus.ORDER_COMPLETED, OrderStatus.RETURN_REQUESTED));

        // Estados de devolución
        map.put(OrderStatus.RETURN_REQUESTED, EnumSet.of(
                OrderStatus.RETURN_APPROVED, OrderStatus.RETURN_REJECTED, OrderStatus.MANUAL_REVIEW));

        map.put(OrderStatus.RETURN_APPROVED, EnumSet.of(
                OrderStatus.RETURN_IN_TRANSIT, OrderStatus.REFUND_PROCESSING));

        map.put(OrderStatus.RETURN_IN_TRANSIT, EnumSet.of(
                OrderStatus.RETURN_RECEIVED, OrderStatus.DELIVERY_EXCEPTION));

        map.put(OrderStatus.RETURN_RECEIVED, EnumSet.of(
                OrderStatus.REFUND_PROCESSING, OrderStatus.ORDER_COMPENSATED));

        map.put(OrderStatus.RETURN_REJECTED, EnumSet.of(
                OrderStatus.ORDER_COMPLETED, OrderStatus.MANUAL_REVIEW));

        // Estados de compensación
        map.put(OrderStatus.REFUND_PROCESSING, EnumSet.of(
                OrderStatus.REFUND_COMPLETED, OrderStatus.ORDER_COMPENSATED, OrderStatus.TECHNICAL_EXCEPTION));

        map.put(OrderStatus.REFUND_COMPLETED, EnumSet.of(
                OrderStatus.ORDER_COMPENSATED, OrderStatus.ORDER_COMPLETED));

        map.put(OrderStatus.ORDER_COMPENSATED, EnumSet.of(
                OrderStatus.ORDER_COMPLETED, OrderStatus.ORDER_CANCELED));

        // Estados técnicos
        map.put(OrderStatus.SYSTEM_PROCESSING, EnumSet.of(
                OrderStatus.ORDER_PROCESSING, OrderStatus.TECHNICAL_EXCEPTION, OrderStatus.WAITING_RETRY));

        map.put(OrderStatus.TECHNICAL_EXCEPTION, EnumSet.of(
                OrderStatus.WAITING_RETRY, OrderStatus.MANUAL_REVIEW, OrderStatus.ORDER_FAILED));

        map.put(OrderStatus.WAITING_RETRY, EnumSet.of(
                OrderStatus.ORDER_PROCESSING, OrderStatus.PAYMENT_PROCESSING, OrderStatus.STOCK_CHECKING,
                OrderStatus.SHIPPING_PENDING, OrderStatus.TECHNICAL_EXCEPTION, OrderStatus.MANUAL_REVIEW));

        map.put(OrderStatus.MANUAL_REVIEW, EnumSet.of(
                OrderStatus.ORDER_PROCESSING, OrderStatus.ORDER_COMPLETED, OrderStatus.ORDER_FAILED,
                OrderStatus.ORDER_CANCELED, OrderStatus.REFUND_PROCESSING));

        // Estados terminales (no tienen transiciones salientes)
        map.put(OrderStatus.ORDER_COMPLETED, EnumSet.noneOf(OrderStatus.class));
        map.put(OrderStatus.ORDER_CANCELED, EnumSet.noneOf(OrderStatus.class));
        map.put(OrderStatus.ORDER_FAILED, EnumSet.noneOf(OrderStatus.class));
        map.put(OrderStatus.ORDER_ABORTED, EnumSet.noneOf(OrderStatus.class));

        return map;
    }

    private Map<String, String> initializeTopicMappings() {
        Map<String, String> map = new HashMap<>();

        // Mapeos de transiciones a topics usando los nombres de EventTopics
        map.put("ORDER_UNKNOWN->ORDER_CREATED", "order-created");
        map.put("ORDER_CREATED->ORDER_PROCESSING", "order-processing");
        map.put("PAYMENT_CONFIRMED->STOCK_RESERVED", "stock-reserved");
        map.put("STOCK_RESERVED->ORDER_COMPLETED", "order-completed");
        map.put("ORDER_CREATED->ORDER_FAILED", "order-failed");
        map.put("ORDER_UNKNOWN->ORDER_FAILED", "order-failed");
        map.put("ORDER_PROCESSING->ORDER_COMPLETED", "order-completed");
        map.put("ORDER_PREPARED->ORDER_COMPLETED", "order-completed");
        map.put("SHIPPING_PENDING->ORDER_COMPLETED", "order-completed");
        map.put("DELIVERED->ORDER_COMPLETED", "order-completed");

        return map;
    }
}