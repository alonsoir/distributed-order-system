package com.example.order.exception;

/**
 * Excepción para errores de timeout en base de datos
 */
public class DatabaseTimeoutException extends DatabaseException {
    public DatabaseTimeoutException(String message) {
        super(message);
    }

    public DatabaseTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}