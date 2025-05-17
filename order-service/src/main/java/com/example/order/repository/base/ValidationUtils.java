package com.example.order.repository.base;

import com.example.order.domain.DeliveryMode;
import com.example.order.exception.InvalidParameterException;

/**
 * Utilidades para validación de parámetros
 */
public class ValidationUtils {
    // Constantes para validación
    private static final int MAX_EVENT_ID_LENGTH = 64;
    private static final int MAX_CORRELATION_ID_LENGTH = 64;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1024;
    private static final int MAX_ERROR_TYPE_LENGTH = 128;
    private static final int MAX_ERROR_CATEGORY_LENGTH = 64;
    private static final int MAX_STATUS_LENGTH = 64;
    private static final int MAX_OPERATION_LENGTH = 32;
    private static final int MAX_OUTCOME_LENGTH = 32;
    private static final int MAX_STEP_NAME_LENGTH = 64;
    private static final int MAX_RESOURCE_ID_LENGTH = 100;

    // Patrones regex para validación
    private static final String ALPHANUMERIC_PATTERN = "^[a-zA-Z0-9\\-_]+$";
    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    /**
     * Trunca una cadena si excede la longitud máxima
     */
    public String truncateIfNeeded(String input, int maxLength) {
        if (input == null) {
            return null;
        }
        return input.length() > maxLength ? input.substring(0, maxLength) : input;
    }

    // Métodos de validación

    public void validateEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new InvalidParameterException("eventId cannot be null or empty");
        }
        if (eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw new InvalidParameterException("eventId must be less than " + MAX_EVENT_ID_LENGTH + " characters");
        }
        if (!eventId.matches(ALPHANUMERIC_PATTERN)) {
            throw new InvalidParameterException("eventId must contain only letters, numbers, dashes and underscores");
        }
    }

    public void validateCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new InvalidParameterException("correlationId cannot be null or empty");
        }
        if (correlationId.length() > MAX_CORRELATION_ID_LENGTH) {
            throw new InvalidParameterException("correlationId must be less than " + MAX_CORRELATION_ID_LENGTH + " characters");
        }
        if (!correlationId.matches(ALPHANUMERIC_PATTERN)) {
            throw new InvalidParameterException("correlationId must contain only letters, numbers, dashes and underscores");
        }
    }

    public void validateDeliveryMode(DeliveryMode deliveryMode) {
        if (deliveryMode == null) {
            throw new InvalidParameterException("deliveryMode cannot be null");
        }
    }

    public void validateOrderId(Long orderId) {
        if (orderId == null) {
            throw new InvalidParameterException("orderId cannot be null");
        }
    }

    public void validateStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new InvalidParameterException("status cannot be null or empty");
        }
        if (status.length() > MAX_STATUS_LENGTH) {
            throw new InvalidParameterException("status must be less than " + MAX_STATUS_LENGTH + " characters");
        }
    }

    public void validateStepName(String stepName) {
        if (stepName == null || stepName.isBlank()) {
            throw new InvalidParameterException("stepName cannot be null or empty");
        }
        if (stepName.length() > MAX_STEP_NAME_LENGTH) {
            throw new InvalidParameterException("stepName must be less than " + MAX_STEP_NAME_LENGTH + " characters");
        }
    }

    public void validateOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new InvalidParameterException("operation cannot be null or empty");
        }
        if (operation.length() > MAX_OPERATION_LENGTH) {
            throw new InvalidParameterException("operation must be less than " + MAX_OPERATION_LENGTH + " characters");
        }
    }

    public void validateOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            throw new InvalidParameterException("outcome cannot be null or empty");
        }
        if (outcome.length() > MAX_OUTCOME_LENGTH) {
            throw new InvalidParameterException("outcome must be less than " + MAX_OUTCOME_LENGTH + " characters");
        }
    }

    public void validateResourceId(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new InvalidParameterException("resourceId cannot be null or empty");
        }
        if (resourceId.length() > MAX_RESOURCE_ID_LENGTH) {
            throw new InvalidParameterException("resourceId must be less than " + MAX_RESOURCE_ID_LENGTH + " characters");
        }
        if (!resourceId.matches(ALPHANUMERIC_PATTERN)) {
            throw new InvalidParameterException("resourceId must contain only letters, numbers, dashes and underscores");
        }
    }

    public void validateEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new InvalidParameterException("eventType cannot be null or empty");
        }
    }

    // Getters para constantes de longitud máxima
    public int getMaxEventIdLength() { return MAX_EVENT_ID_LENGTH; }
    public int getMaxCorrelationIdLength() { return MAX_CORRELATION_ID_LENGTH; }
    public int getMaxErrorMessageLength() { return MAX_ERROR_MESSAGE_LENGTH; }
    public int getMaxErrorTypeLength() { return MAX_ERROR_TYPE_LENGTH; }
    public int getMaxErrorCategoryLength() { return MAX_ERROR_CATEGORY_LENGTH; }
    public int getMaxStatusLength() { return MAX_STATUS_LENGTH; }
    public int getMaxOperationLength() { return MAX_OPERATION_LENGTH; }
    public int getMaxOutcomeLength() { return MAX_OUTCOME_LENGTH; }
    public int getMaxStepNameLength() { return MAX_STEP_NAME_LENGTH; }
}