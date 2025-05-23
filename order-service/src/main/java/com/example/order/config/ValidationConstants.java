package com.example.order.config;

/**
 * Constantes relacionadas con validaciones y límites
 */
public final class ValidationConstants {

    private ValidationConstants() {
        // Utility class
    }

    // Field limits (basado en schema-tables.sql)
    public static final int EXTERNAL_REFERENCE_MAX_LENGTH = 36;
    public static final int CORRELATION_ID_MAX_LENGTH = 50;
    public static final int EVENT_ID_MAX_LENGTH = 50;

    // Validation messages
    public static final String MSG_EXTERNAL_REF_NULL = "External reference cannot be null or empty";
    public static final String MSG_EXTERNAL_REF_TOO_LONG = "External reference cannot be longer than 36 characters";
    public static final String MSG_QUANTITY_POSITIVE = "Quantity must be positive";
    public static final String MSG_AMOUNT_NON_NEGATIVE = "Amount must be non-negative";
}