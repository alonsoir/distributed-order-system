package com.example.order.service;

import com.example.order.domain.OrderStateMachine;
import com.example.order.domain.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Servicio híbrido para gestión de máquinas de estado de órdenes
 * - Mantiene reglas estáticas para validaciones rápidas
 * - Crea instancias específicas para cada orden cuando se necesita estado
 */
@Slf4j
@Service
public class OrderStateMachineService {

    // Instancia estática para reglas y validaciones rápidas
    private static final OrderStateMachine STATIC_RULES = new OrderStateMachine();

    // Cache de instancias por orden (opcional, para performance)
    private final ConcurrentMap<Long, OrderStateMachine> orderStateMachines = new ConcurrentHashMap<>();

    /**
     * Validación rápida de transición usando reglas estáticas
     */
    public boolean isValidTransition(OrderStatus from, OrderStatus to) {
        return STATIC_RULES.isValidTransition(from, to);
    }

    /**
     * Obtiene estados válidos siguientes usando reglas estáticas
     */
    public Set<OrderStatus> getValidNextStates(OrderStatus currentStatus) {
        return STATIC_RULES.getValidNextStates(currentStatus);
    }

    /**
     * Obtiene topic para transición usando reglas estáticas
     */
    public String getTopicForTransition(OrderStatus from, OrderStatus to) {
        return STATIC_RULES.getTopicNameForTransition(from, to);
    }

    /**
     * Verifica si un estado es terminal usando reglas estáticas
     */
    public boolean isTerminalState(OrderStatus status) {
        return STATIC_RULES.isTerminalState(status);
    }

    /**
     * Calcula distancia entre estados usando reglas estáticas
     */
    public int calculateDistanceBetweenStates(OrderStatus from, OrderStatus to) {
        return STATIC_RULES.calculateDistanceBetweenStates(from, to);
    }

    /**
     * Obtiene el mejor estado siguiente hacia un objetivo usando reglas estáticas
     */
    public OrderStatus getBestNextStateTowards(OrderStatus currentStatus, OrderStatus targetStatus) {
        return STATIC_RULES.getBestNextStateTowards(currentStatus, targetStatus);
    }

    /**
     * Crea una nueva instancia de máquina de estados para una orden específica
     */
    public OrderStateMachine createForOrder(Long orderId, OrderStatus initialStatus) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId cannot be null");
        }
        if (initialStatus == null) {
            throw new IllegalArgumentException("initialStatus cannot be null");
        }

        log.debug("Creating OrderStateMachine for order {} with initial status {}", orderId, initialStatus);

        OrderStateMachine stateMachine = new OrderStateMachine(orderId, initialStatus);

        // Opcional: cachear la instancia para reuso
        orderStateMachines.put(orderId, stateMachine);

        return stateMachine;
    }

    /**
     * Obtiene o crea una máquina de estados para una orden
     */
    public OrderStateMachine getOrCreateForOrder(Long orderId, OrderStatus currentStatus) {
        return orderStateMachines.computeIfAbsent(orderId,
                id -> new OrderStateMachine(id, currentStatus));
    }

    /**
     * Obtiene una máquina de estados existente para una orden
     */
    public OrderStateMachine getForOrder(Long orderId) {
        return orderStateMachines.get(orderId);
    }

    /**
     * Remueve la máquina de estados de una orden (para cleanup)
     */
    public void removeForOrder(Long orderId) {
        if (orderId != null) {
            OrderStateMachine removed = orderStateMachines.remove(orderId);
            if (removed != null) {
                log.debug("Removed OrderStateMachine for order {}", orderId);
            }
        }
    }

    /**
     * Limpia máquinas de estados en estados terminales (para cleanup periódico)
     */
    public void cleanupTerminalStates() {
        orderStateMachines.entrySet().removeIf(entry -> {
            OrderStateMachine stateMachine = entry.getValue();
            boolean isTerminal = isTerminalState(stateMachine.getCurrentStatus());
            if (isTerminal) {
                log.debug("Cleaning up terminal state machine for order {}", entry.getKey());
            }
            return isTerminal;
        });
    }

    /**
     * Obtiene el número de máquinas de estado activas (para monitoreo)
     */
    public int getActiveStateMachineCount() {
        return orderStateMachines.size();
    }
}