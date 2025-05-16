package com.example.order.exception;

/**
 * Excepción base para todas las excepciones de la aplicación
 */
public class ApplicationException extends RuntimeException {
    public ApplicationException(String message) {
        super(message);
    }

    public ApplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}