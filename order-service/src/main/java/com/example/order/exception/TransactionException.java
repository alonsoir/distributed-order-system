package com.example.order.exception;

/**
 * Excepción para errores de transacción
 */
public class TransactionException extends DatabaseException {
    public TransactionException(String message) {
        super(message);
    }

    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}