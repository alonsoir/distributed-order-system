package com.example.order.events;

import com.example.order.domain.OrderStatus;

import java.util.Arrays;
import java.util.Optional;

/**
 * Gestor unificado para los nombres de topics basado en OrderStatus.
 * Elimina la duplicación y proporciona una forma centralizada y tipo-segura
 * de acceder a todos los topics de eventos.
 */
public class EventTopics {

    /**
     * Tipos de topics especiales para manejo de errores y flujos alternativos
     */
    public enum TopicType {
        NORMAL(""),           // Topic normal de procesamiento (sin sufijo)
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

    /**
     * Convierte un OrderStatus a nombre de topic
     * @param status El estado de la orden
     * @return El nombre del topic correspondiente
     */
    public static String getTopicName(OrderStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("OrderStatus cannot be null");
        }

        // Convertir de UPPER_CASE a kebab-case
        return status.name().toLowerCase().replace('_', '-');
    }

    /**
     * Obtiene el nombre del topic para un tipo específico
     * @param status El estado de la orden
     * @param type El tipo de topic requerido
     * @return El nombre del topic del tipo especificado
     */
    public static String getTopicForType(OrderStatus status, TopicType type) {
        String baseTopic = getTopicName(status);

        if (type == TopicType.NORMAL) {
            return baseTopic;
        }

        return baseTopic + type.getSuffix();
    }

    /**
     * Obtiene el nombre de DLQ para un estado
     */
    public static String getDlqTopic(OrderStatus status) {
        return getTopicForType(status, TopicType.DLQ);
    }

    /**
     * Obtiene el nombre del topic de reintentos para un estado
     */
    public static String getRetryTopic(OrderStatus status) {
        return getTopicForType(status, TopicType.RETRY);
    }

    /**
     * Obtiene el nombre del topic de auditoría para un estado
     */
    public static String getAuditTopic(OrderStatus status) {
        return getTopicForType(status, TopicType.AUDIT);
    }

    /**
     * Convierte un nombre de topic de vuelta a OrderStatus
     * @param topicName El nombre del topic
     * @return El OrderStatus correspondiente
     */
    public static Optional<OrderStatus> fromTopicName(String topicName) {
        if (topicName == null) {
            return Optional.empty();
        }

        // Extraer el topic base (sin sufijos como .dlq, .retry)
        String baseTopic = extractBaseTopicName(topicName);

        // Convertir de kebab-case a UPPER_CASE
        String statusName = baseTopic.toUpperCase().replace('-', '_');

        try {
            return Optional.of(OrderStatus.valueOf(statusName));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Extrae el nombre del topic base a partir de un topic derivado
     */
    public static String extractBaseTopicName(String derivedTopicName) {
        TopicType type = TopicType.fromTopicName(derivedTopicName);

        if (type == TopicType.NORMAL) {
            return derivedTopicName;
        }

        return derivedTopicName.substring(0, derivedTopicName.length() - type.getSuffix().length());
    }

    /**
     * Verifica si un nombre de topic es un topic de DLQ
     */
    public static boolean isDlqTopic(String topicName) {
        return TopicType.fromTopicName(topicName) == TopicType.DLQ;
    }

    /**
     * Verifica si un nombre de topic es un topic de reintentos
     */
    public static boolean isRetryTopic(String topicName) {
        return TopicType.fromTopicName(topicName) == TopicType.RETRY;
    }

    /**
     * Obtiene todos los nombres de topics normales
     */
    public static String[] getAllTopicNames() {
        return Arrays.stream(OrderStatus.values())
                .map(EventTopics::getTopicName)
                .toArray(String[]::new);
    }

    /**
     * Obtiene todos los nombres de topics para un tipo específico
     */
    public static String[] getTopicNamesByType(TopicType type) {
        return Arrays.stream(OrderStatus.values())
                .map(status -> getTopicForType(status, type))
                .toArray(String[]::new);
    }

    /**
     * Obtiene todos los nombres de topics de DLQ
     */
    public static String[] getAllDlqTopicNames() {
        return getTopicNamesByType(TopicType.DLQ);
    }

    // Métodos de conveniencia para estados comunes
    public static final class Common {
        public static final String ORDER_CREATED = getTopicName(OrderStatus.ORDER_CREATED);
        public static final String ORDER_FAILED = getTopicName(OrderStatus.ORDER_FAILED);
        public static final String ORDER_COMPLETED = getTopicName(OrderStatus.ORDER_COMPLETED);
        public static final String STOCK_RESERVED = getTopicName(OrderStatus.STOCK_RESERVED);
        public static final String PAYMENT_CONFIRMED = getTopicName(OrderStatus.PAYMENT_CONFIRMED);
        public static final String ORDER_PROCESSING = getTopicName(OrderStatus.ORDER_PROCESSING);
        public static final String TECHNICAL_EXCEPTION = getTopicName(OrderStatus.TECHNICAL_EXCEPTION);
    }
}