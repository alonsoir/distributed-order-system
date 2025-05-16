package com.example.order.exception;

/**
 * Excepción para errores de mapeo de filas de base de datos
 */
public class DatabaseMappingException extends DatabaseException {
    public DatabaseMappingException(String message) {
        super(message);
    }

    public DatabaseMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}