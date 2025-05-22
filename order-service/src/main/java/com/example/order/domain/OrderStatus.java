package com.example.order.domain;

/**
 * Enum que representa los diferentes estados de una orden
 * Tanto para las tuplas de la bd como para los eventos.
 **/
public enum OrderStatus {
    // Estados iniciales
    ORDER_CREATED("ORDER_CREATED"),                     // Orden creada inicialmente
    ORDER_VALIDATED("ORDER_VALIDATED"),                 // Datos de la orden validados

    // Estados de pago
    PAYMENT_PENDING("PAYMENT_PENDING"),                 // Esperando confirmación de pago
    PAYMENT_PROCESSING("PAYMENT_PROCESSING"),           // Procesando el pago
    PAYMENT_CONFIRMED("PAYMENT_CONFIRMED"),             // Pago confirmado
    PAYMENT_DECLINED("PAYMENT_DECLINED"),               // Pago rechazado

    // Estados de inventario
    STOCK_CHECKING("STOCK_CHECKING"),                   // Verificando disponibilidad
    STOCK_RESERVED("STOCK_RESERVED"),                   // Inventario reservado
    STOCK_UNAVAILABLE("STOCK_UNAVAILABLE"),             // Inventario no disponible

    // Estados de preparación
    ORDER_PENDING("ORDER_PENDING"),                     // Orden pendiente de procesamiento
    ORDER_PROCESSING("ORDER_PROCESSING"),               // Orden en procesamiento
    ORDER_PREPARED("ORDER_PREPARED"),                   // Orden preparada para envío

    // Estados de envío
    SHIPPING_PENDING("SHIPPING_PENDING"),               // Pendiente de envío
    SHIPPING_ASSIGNED("SHIPPING_ASSIGNED"),             // Servicio de envío asignado
    SHIPPING_IN_PROGRESS("SHIPPING_IN_PROGRESS"),       // Envío en curso
    SHIPPING_DELAYED("SHIPPING_DELAYED"),               // Envío con retraso
    SHIPPING_EXCEPTION("SHIPPING_EXCEPTION"),           // Problema durante el envío

    // Estados de entrega
    DELIVERED_TO_COURIER("DELIVERED_TO_COURIER"),       // Entregado al courier
    OUT_FOR_DELIVERY("OUT_FOR_DELIVERY"),               // En ruta final de entrega
    DELIVERED("DELIVERED"),                             // Entregado al cliente
    DELIVERY_ATTEMPTED("DELIVERY_ATTEMPTED"),           // Intento de entrega fallido
    DELIVERY_EXCEPTION("DELIVERY_EXCEPTION"),           // Problema durante la entrega

    // Estados de recepción
    PENDING_CONFIRMATION("PENDING_CONFIRMATION"),       // Pendiente confirmación del cliente
    RECEIVED_CONFIRMED("RECEIVED_CONFIRMED"),           // Recepción confirmada por cliente

    // Estados de devolución
    RETURN_REQUESTED("RETURN_REQUESTED"),               // Devolución solicitada
    RETURN_APPROVED("RETURN_APPROVED"),                 // Devolución aprobada
    RETURN_IN_TRANSIT("RETURN_IN_TRANSIT"),             // Devolución en tránsito
    RETURN_RECEIVED("RETURN_RECEIVED"),                 // Devolución recibida
    RETURN_REJECTED("RETURN_REJECTED"),                 // Devolución rechazada

    // Estados de compensación/reembolso
    REFUND_PROCESSING("REFUND_PROCESSING"),             // Procesando reembolso
    REFUND_COMPLETED("REFUND_COMPLETED"),               // Reembolso completado
    ORDER_COMPENSATED("ORDER_COMPENSATED"),             // Orden compensada (parcial/total)

    // Estados técnicos y de sistema
    SYSTEM_PROCESSING("SYSTEM_PROCESSING"),             // Procesando en sistema
    TECHNICAL_EXCEPTION("TECHNICAL_EXCEPTION"),         // Excepción técnica
    WAITING_RETRY("WAITING_RETRY"),                     // Esperando reintento
    MANUAL_REVIEW("MANUAL_REVIEW"),                     // Requiere revisión manual

    // Estados terminales
    ORDER_COMPLETED("ORDER_COMPLETED"),                 // Orden completada satisfactoriamente
    ORDER_CANCELED("ORDER_CANCELED"),                   // Orden cancelada
    ORDER_FAILED("ORDER_FAILED"),                       // Orden fallida por error
    ORDER_ABORTED("ORDER_ABORTED"),                     // Orden abortada por sistema

    // Estado especial
    ORDER_UNKNOWN("ORDER_UNKNOWN");
    private final String value;

    OrderStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static OrderStatus fromValue(String value) {
        for (OrderStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid order status: " + value);
    }
}