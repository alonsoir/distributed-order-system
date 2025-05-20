package com.example.order.repository.base;

import com.example.order.domain.OrderStatus;
import org.owasp.encoder.Encode;

/**
 * Utilidades para seguridad y prevención de inyección SQL
 * Solución temporal hasta implementar procedimientos almacenados
 */
public class SecurityUtils {

    /**
     * Sanitiza un UUID para uso en el sistema de bloqueo
     * @param lockUuid UUID a sanitizar
     * @return lockUuid sanitizado
     */
    public String sanitizeLockUuid(String lockUuid) {
        if (lockUuid == null || lockUuid.isBlank()) {
            throw new IllegalArgumentException("lockUuid cannot be null or empty");
        }

        if (lockUuid.length() != 36) {
            throw new IllegalArgumentException("lockUuid must be exactly 36 characters (UUID format)");
        }

        // Validar formato UUID
        if (!lockUuid.matches("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$")) {
            throw new IllegalArgumentException("lockUuid must be a valid UUID format");
        }

        return Encode.forHtml(lockUuid);
    }
    /**
     * Sanitiza la entrada para prevenir inyección SQL
     * @param status OrderStatus a sanitizar
     * @return representación sanitizada del valor del enum
     */
    public String sanitizeStatus(OrderStatus status) {
        if (status == null) {
            return OrderStatus.ORDER_UNKNOWN.getValue(); // Asegúrate de que este valor exista en tu enum
        }

        // Obtener el valor del enum
        String value = status.getValue();

        // Aunque no es necesario sanitizar un enum (ya son valores seguros),
        // mantenemos esto como precaución adicional temporal
        return Encode.forHtml(value);
    }

    /**
     * Valida un valor Long
     * @param input Long a validar
     * @return el mismo valor Long o null si la entrada es null
     */
    public Long sanitizeLongInput(Long input) {
        // No es necesario sanitizar un Long ya que es un tipo primitivo seguro
        return input;
    }

    /**
     * Sanitiza un resourceId para uso en el sistema de bloqueo de transacciones
     * @param resourceId ID del recurso a sanitizar
     * @return resourceId sanitizado
     * @throws IllegalArgumentException si el formato es inválido
     */
    public String sanitizeResourceId(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId cannot be null or empty");
        }

        if (resourceId.length() > 100) {
            throw new IllegalArgumentException("resourceId must be less than 100 characters");
        }

        // Validar que solo contenga caracteres permitidos
        if (!resourceId.matches("^[a-zA-Z0-9\\-_]+$")) {
            throw new IllegalArgumentException("resourceId must contain only letters, numbers, dashes and underscores");
        }

        // Al ser un valor controlado, solo hacemos encode básico
        return Encode.forHtml(resourceId);
    }

    /**
     * Sanitiza un correlationId
     * @param correlationId ID de correlación a sanitizar
     * @return correlationId sanitizado
     * @throws IllegalArgumentException si el formato es inválido
     */
    public String sanitizeCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId cannot be null or empty");
        }

        if (correlationId.length() > 36) {
            throw new IllegalArgumentException("correlationId must be less than 37 characters");
        }

        // Validar que solo contenga caracteres permitidos (UUID pattern)
        if (!correlationId.matches("^[a-zA-Z0-9\\-_]+$")) {
            throw new IllegalArgumentException("correlationId must contain only letters, numbers, dashes and underscores");
        }

        return Encode.forHtml(correlationId);
    }

    /**
     * Sanitiza un valor String de entrada
     * @param input String a sanitizar
     * @return entrada sanitizada
     */
    public String sanitizeStringInput(String input) {
        if (input == null) {
            return null;
        }

        // Eliminar caracteres potencialmente peligrosos para SQL
        String sanitized = input.replaceAll("[;'\"]", "");

        // Usar OWASP Encoder para codificar HTML y SQL
        return Encode.forHtml(sanitized);
    }

    /**
     * Sanitiza y codifica específicamente para mensajes de error
     * que pueden contener datos más complejos
     */
    public String sanitizeErrorMessage(String message, int maxLength) {
        if (message == null) {
            return "No error message provided";
        }

        // Truncar si es necesario
        if (message.length() > maxLength) {
            message = message.substring(0, maxLength);
        }

        // Encodar para HTML
        return Encode.forHtml(message);
    }
}