package com.example.order.exception;

/**
 * Excepción para errores de orden no encontrada
 */
public class OrderNotFoundException extends ApplicationException {
    public OrderNotFoundException(String message) {
        super(message);
    }

    public OrderNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}