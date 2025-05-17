package com.example.order.repository.base;

import org.owasp.encoder.Encode;

/**
 * Utilidades para seguridad y prevención de inyección SQL
 */
public class SecurityUtils {

    /**
     * Sanitiza la entrada para prevenir inyección SQL
     * @param input entrada a sanitizar
     * @return entrada sanitizada
     */
    public String sanitizeInput(String input) {
        if (input == null) {
            return null;
        }

        // 1. Eliminar caracteres potencialmente peligrosos para SQL
        String sanitized = input.replaceAll("[;'\"]", "");

        // 2. Usar OWASP Encoder para codificar HTML y SQL
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