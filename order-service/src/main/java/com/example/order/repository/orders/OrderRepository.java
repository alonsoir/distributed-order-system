package com.example.order.repository.orders;

import com.example.order.domain.DeliveryMode;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.events.OrderEvent;
import reactor.core.publisher.Mono;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Interfaz para operaciones relacionadas con órdenes en la base de datos.
 * Define el contrato para el acceso a datos de órdenes.
 */
public interface OrderRepository {

    /**
     * Busca una orden por su ID
     * @param orderId ID de la orden a buscar
     * @return Mono con la orden encontrada o error si no existe
     */
    Mono<Order> findOrderById(@NotNull Long orderId);

    /**
     * Obtiene el estado actual de una orden
     * @param orderId ID de la orden
     * @return Mono con el estado actual de la orden
     */
    Mono<OrderStatus> getOrderStatus(@NotNull Long orderId);

    /**
     * Guarda los datos de una nueva orden (usa DeliveryMode.AT_LEAST_ONCE por defecto)
     * @param orderId ID de la orden
     * @param correlationId ID de correlación
     * @param eventId ID del evento
     * @param event Evento de la orden
     * @return Mono que completa cuando se guarda exitosamente
     */
    Mono<Void> saveOrderData(
            @NotNull Long orderId,
            @NotNull @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$") String correlationId,
            @NotNull @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$") String eventId,
            @NotNull OrderEvent event);

    /**
     * Guarda los datos de una nueva orden con modo de entrega específico
     * @param orderId ID de la orden
     * @param correlationId ID de correlación
     * @param eventId ID del evento
     * @param event Evento de la orden
     * @param deliveryMode Modo de entrega (AT_LEAST_ONCE o AT_MOST_ONCE)
     * @return Mono que completa cuando se guarda exitosamente
     */
    Mono<Void> saveOrderData(
            @NotNull Long orderId,
            @NotNull @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$") String correlationId,
            @NotNull @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$") String eventId,
            @NotNull OrderEvent event,
            DeliveryMode deliveryMode);

    /**
     * Actualiza el estado de una orden
     * @param orderId ID de la orden
     * @param status Nuevo estado de la orden
     * @param correlationId ID de correlación
     * @return Mono con la orden actualizada
     */
    Mono<Order> updateOrderStatus(
            @NotNull Long orderId,
            @NotNull OrderStatus status,
            @NotNull @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$") String correlationId);

    /**
     * Inserta un registro en el historial de estados de la orden
     * @param orderId ID de la orden
     * @param status Estado de la orden
     * @param correlationId ID de correlación
     * @return Mono que completa cuando se inserta exitosamente
     */
    Mono<Void> insertStatusAuditLog(
            @NotNull Long orderId,
            @NotNull OrderStatus status,
            @NotNull @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$") String correlationId);
}