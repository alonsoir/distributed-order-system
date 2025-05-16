package com.example.order.exception;

/**
 * Excepción para errores de validación de parámetros
 */
public class InvalidParameterException extends ApplicationException {
    public InvalidParameterException(String message) {
        super(message);
    }

    public InvalidParameterException(String message, Throwable cause) {
        super(message, cause);
    }
}