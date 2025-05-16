package com.example.order.exception;

/**
 * Excepción para errores de base de datos
 */
public class DatabaseException extends ApplicationException {
    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}