package com.example.order.exception;

/**
 * Excepción para errores de timeout en transacciones
 */
public class TransactionTimeoutException extends TransactionException {
    public TransactionTimeoutException(String message) {
        super(message);
    }

    public TransactionTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}