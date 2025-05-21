package com.example.order.events;

import com.example.order.domain.OrderStatus;

import java.util.Arrays;
import java.util.Optional;

/**
 * Gestor unificado para los nombres de topics utilizados en el sistema de mensajería.
 * Proporciona una forma centralizada y tipo-segura de acceder a todos los topics,
 * incluyendo variantes para manejo de errores como DLQ, reintentos, auditoría, etc.
 */
public enum EventTopics {
    // Estados iniciales
    ORDER_CREATED("order-created"),                     // Orden creada inicialmente
    ORDER_VALIDATED("order-validated"),                 // Datos de la orden validados

    // Estados de pago
    PAYMENT_PENDING("payment-pending"),                 // Esperando confirmación de pago
    PAYMENT_PROCESSING("payment-processing"),           // Procesando el pago
    PAYMENT_CONFIRMED("payment-confirmed"),             // Pago confirmado
    PAYMENT_DECLINED("payment-declined"),               // Pago rechazado

    // Estados de inventario
    STOCK_CHECKING("stock-checking"),                   // Verificando disponibilidad
    STOCK_RESERVED("stock-reserved"),                   // Inventario reservado
    STOCK_UNAVAILABLE("stock-unavailable"),             // Inventario no disponible

    // Estados de preparación
    ORDER_PENDING("order-pending"),                     // Orden pendiente de procesamiento
    ORDER_PROCESSING("order-processing"),               // Orden en procesamiento
    ORDER_PREPARED("order-prepared"),                   // Orden preparada para envío

    // Estados de envío
    SHIPPING_PENDING("shipping-pending"),               // Pendiente de envío
    SHIPPING_ASSIGNED("shipping-assigned"),             // Servicio de envío asignado
    SHIPPING_IN_PROGRESS("shipping-in-progress"),       // Envío en curso
    SHIPPING_DELAYED("shipping-delayed"),               // Envío con retraso
    SHIPPING_EXCEPTION("shipping-exception"),           // Problema durante el envío

    // Estados de entrega
    DELIVERED_TO_COURIER("delivered-to-courier"),       // Entregado al courier
    OUT_FOR_DELIVERY("out-for-delivery"),               // En ruta final de entrega
    DELIVERED("delivered"),                             // Entregado al cliente
    DELIVERY_ATTEMPTED("delivery-attempted"),           // Intento de entrega fallido
    DELIVERY_EXCEPTION("delivery-exception"),           // Problema durante la entrega

    // Estados de recepción
    PENDING_CONFIRMATION("pending-confirmation"),       // Pendiente confirmación del cliente
    RECEIVED_CONFIRMED("received-confirmed"),           // Recepción confirmada por cliente

    // Estados de devolución
    RETURN_REQUESTED("return-requested"),               // Devolución solicitada
    RETURN_APPROVED("return-approved"),                 // Devolución aprobada
    RETURN_IN_TRANSIT("return-in-transit"),             // Devolución en tránsito
    RETURN_RECEIVED("return-received"),                 // Devolución recibida
    RETURN_REJECTED("return-rejected"),                 // Devolución rechazada

    // Estados de compensación/reembolso
    REFUND_PROCESSING("refund-processing"),             // Procesando reembolso
    REFUND_COMPLETED("refund-completed"),               // Reembolso completado
    ORDER_COMPENSATED("order-compensated"),             // Orden compensada (parcial/total)

    // Estados técnicos y de sistema
    SYSTEM_PROCESSING("system-processing"),             // Procesando en sistema
    TECHNICAL_EXCEPTION("technical-exception"),         // Excepción técnica
    WAITING_RETRY("waiting-retry"),                     // Esperando reintento
    MANUAL_REVIEW("manual-review"),                     // Requiere revisión manual

    // Estados terminales
    ORDER_COMPLETED("order-completed"),                 // Orden completada satisfactoriamente
    ORDER_CANCELED("order-canceled"),                   // Orden cancelada
    ORDER_FAILED("order-failed"),                       // Orden fallida por error
    ORDER_ABORTED("order-aborted"),                     // Orden abortada por sistema

    // Estado especial
    ORDER_UNKNOWN("order-unknown");                     // Estado desconocido

    /**
     * Tipos de topics especiales para manejo de errores y flujos alternativos
     */
    public enum TopicType {
        NORMAL("."),          // Topic normal de procesamiento (sin sufijo)
        DLQ(".dlq"),          // Dead Letter Queue
        RETRY(".retry"),      // Topic para reintentos
        AUDIT(".audit"),      // Topic para auditoría
        ARCHIVE(".archive"),  // Topic para archivado de mensajes
        BATCH(".batch");      // Topic para procesamiento por lotes

        private final String suffix;

        TopicType(String suffix) {
            this.suffix = suffix;
        }

        public String getSuffix() {
            return suffix;
        }

        /**
         * Determina el tipo de topic basado en el nombre
         * @param topicName El nombre del topic a analizar
         * @return El tipo de topic
         */
        public static TopicType fromTopicName(String topicName) {
            if (topicName == null) {
                throw new IllegalArgumentException("Topic name cannot be null");
            }

            return Arrays.stream(values())
                    .filter(type -> type != NORMAL && topicName.endsWith(type.getSuffix()))
                    .findFirst()
                    .orElse(NORMAL);
        }
    }

    private final String topicName;

    EventTopics(String topicName) {
        this.topicName = topicName;
    }

    /**
     * Obtiene el nombre base del topic
     * @return El nombre base del topic
     */
    public String getTopicName() {
        return topicName;
    }

    /**
     * Obtiene el nombre del topic para un tipo específico de manejo
     * @param type El tipo de topic requerido
     * @return El nombre del topic del tipo especificado
     */
    public String getTopicForType(TopicType type) {
        if (type == TopicType.NORMAL) {
            return topicName;
        }

        // Para otros tipos, agregamos el sufijo correspondiente
        return topicName + type.getSuffix();
    }

    /**
     * Obtiene el nombre de DLQ para este topic
     * @return El nombre del topic de DLQ
     */
    public String getDlqTopic() {
        return getTopicForType(TopicType.DLQ);
    }

    /**
     * Obtiene el nombre del topic de reintentos para este topic
     * @return El nombre del topic de reintentos
     */
    public String getRetryTopic() {
        return getTopicForType(TopicType.RETRY);
    }

    /**
     * Obtiene el nombre del topic de auditoría para este topic
     * @return El nombre del topic de auditoría
     */
    public String getAuditTopic() {
        return getTopicForType(TopicType.AUDIT);
    }

    /**
     * Extrae el nombre del topic base a partir de un topic derivado
     * @param derivedTopicName El nombre del topic derivado (DLQ, retry, etc.)
     * @return El nombre del topic base
     */
    public static String extractBaseTopicName(String derivedTopicName) {
        TopicType type = TopicType.fromTopicName(derivedTopicName);

        if (type == TopicType.NORMAL) {
            return derivedTopicName;
        }

        return derivedTopicName.substring(0, derivedTopicName.length() - type.getSuffix().length());
    }

    /**
     * Busca un EventTopic a partir de un nombre exacto de topic base
     * @param exactTopicName El nombre exacto del topic base
     * @return El EventTopic correspondiente
     * @throws IllegalArgumentException si no se encuentra un topic que coincida exactamente
     */
    public static EventTopics fromExactTopicName(String exactTopicName) {
        return Arrays.stream(values())
                .filter(topic -> topic.getTopicName().equals(exactTopicName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No matching event topic for: " + exactTopicName));
    }

    /**
     * Busca un EventTopic a partir de un nombre de topic, incluso si es derivado (DLQ, retry, etc.)
     * @param topicName El nombre del topic (normal o derivado)
     * @return El EventTopic correspondiente
     * @throws IllegalArgumentException si no se encuentra un topic que coincida
     */
    public static EventTopics fromAnyTopicName(String topicName) {
        String baseTopic = extractBaseTopicName(topicName);
        return fromExactTopicName(baseTopic);
    }

    /**
     * Obtiene el EventTopic correspondiente a un OrderStatus
     * @param status El estado de la orden
     * @return El EventTopic correspondiente si existe, o Optional.empty() si no hay correspondencia
     */
    public static Optional<EventTopics> fromOrderStatus(OrderStatus status) {
        if (status == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(valueOf(status.name()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Obtiene el EventTopic correspondiente a un OrderStatus
     * @param status El estado de la orden
     * @return El EventTopic correspondiente
     * @throws IllegalArgumentException si no hay un EventTopic correspondiente
     */
    public static EventTopics fromOrderStatusOrThrow(OrderStatus status) {
        return fromOrderStatus(status)
                .orElseThrow(() -> new IllegalArgumentException("No matching event topic for status: " + status));
    }

    /**
     * Verifica si existe un EventTopic para el OrderStatus dado
     * @param status El estado a verificar
     * @return true si existe un topic correspondiente, false en caso contrario
     */
    public static boolean hasTopicForStatus(OrderStatus status) {
        return fromOrderStatus(status).isPresent();
    }

    /**
     * Intenta obtener el OrderStatus correspondiente a este EventTopic
     * @return Optional con el OrderStatus correspondiente, o empty si no hay correspondencia
     */
    public Optional<OrderStatus> toOrderStatus() {
        try {
            return Optional.of(OrderStatus.valueOf(this.name()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Verifica si un nombre de topic es un topic de DLQ
     * @param topicName El nombre del topic a verificar
     * @return true si es un topic de DLQ, false en caso contrario
     */
    public static boolean isDlqTopic(String topicName) {
        return TopicType.fromTopicName(topicName) == TopicType.DLQ;
    }

    /**
     * Verifica si un nombre de topic es un topic de reintentos
     * @param topicName El nombre del topic a verificar
     * @return true si es un topic de reintentos, false en caso contrario
     */
    public static boolean isRetryTopic(String topicName) {
        return TopicType.fromTopicName(topicName) == TopicType.RETRY;
    }

    /**
     * Obtiene todos los nombres de topics para todos los tipos definidos
     * @return Un array con todos los nombres de topics posibles
     */
    public static String[] getAllTopicNames() {
        return Arrays.stream(values())
                .map(EventTopics::getTopicName)
                .toArray(String[]::new);
    }

    /**
     * Obtiene todos los nombres de topics para un tipo específico
     * @param type El tipo de topics a obtener
     * @return Un array con todos los nombres de topics del tipo especificado
     */
    public static String[] getTopicNamesByType(TopicType type) {
        return Arrays.stream(values())
                .map(topic -> topic.getTopicForType(type))
                .toArray(String[]::new);
    }

    /**
     * Obtiene todos los nombres de topics de DLQ
     * @return Un array con todos los nombres de topics de DLQ
     */
    public static String[] getAllDlqTopicNames() {
        return getTopicNamesByType(TopicType.DLQ);
    }
}