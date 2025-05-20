package com.example.order.repository.saga;

import com.example.order.domain.DeliveryMode;
import com.example.order.domain.OrderStatus;
import com.example.order.exception.*;
import com.example.order.repository.base.AbstractReactiveRepository;
import com.example.order.repository.base.SecurityUtils;
import com.example.order.repository.base.ValidationUtils;
import org.owasp.encoder.Encode;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.concurrent.TimeoutException;

/**
 * Repositorio para operaciones relacionadas con fallos de saga
 */
@Repository
@Validated
public class SagaFailureRepository extends AbstractReactiveRepository {

    // SQL Queries
    private static final String SQL_INSERT_STEP_FAILURE =
            "INSERT INTO saga_step_failures (step_name, order_id, correlation_id, event_id, " +
                    "error_message, error_type, error_category, timestamp) " +
                    "VALUES (:stepName, :orderId, :correlationId, :eventId, :errorMessage, :errorType, :errorCategory, CURRENT_TIMESTAMP)";
    private static final String SQL_INSERT_SAGA_FAILURE =
            "INSERT INTO saga_failures (order_id, correlation_id, error_message, error_type, " +
                    "delivery_mode, timestamp) VALUES (:orderId, :correlationId, :errorMessage, " +
                    ":errorType, :deliveryMode, CURRENT_TIMESTAMP)";
    private static final String SQL_INSERT_COMPENSATION_LOG =
            "INSERT INTO compensation_log (step_name, order_id, correlation_id, event_id, status, timestamp) " +
                    "VALUES (:stepName, :orderId, :correlationId, :eventId, :status, CURRENT_TIMESTAMP)";

    public SagaFailureRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils) {
        super(databaseClient, transactionalOperator, securityUtils, validationUtils);
    }

    /**
     * Registra un fallo de paso en la saga
     */
    public Mono<Void> recordStepFailure(
            @NotNull(message = "stepName cannot be null")
            @NotBlank(message = "stepName cannot be empty")
            @Size(max = 64, message = "stepName must be less than {max} characters")
            String stepName,
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = 64, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = 64, message = "eventId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @Size(max = 1024, message = "errorMessage must be less than {max} characters")
            String errorMessage,
            @Size(max = 128, message = "errorType must be less than {max} characters")
            String errorType,
            @Size(max = 64, message = "errorCategory must be less than {max} characters")
            String errorCategory) {

        validationUtils.validateStepName(stepName);
        validationUtils.validateOrderId(orderId);
        validationUtils.validateCorrelationId(correlationId);
        validationUtils.validateEventId(eventId);

        // Manejo de valores potencialmente nulos con valores por defecto
        String sanitizedErrorMessage = errorMessage != null ?
                Encode.forHtml(validationUtils.truncateIfNeeded(errorMessage, validationUtils.getMaxErrorMessageLength())) :
                "No error message provided";

        String sanitizedErrorType = errorType != null ?
                securityUtils.sanitizeStringInput(validationUtils.truncateIfNeeded(errorType, validationUtils.getMaxErrorTypeLength())) :
                "UNKNOWN_ERROR";

        String sanitizedErrorCategory = errorCategory != null ?
                securityUtils.sanitizeStringInput(validationUtils.truncateIfNeeded(errorCategory, validationUtils.getMaxErrorCategoryLength())) :
                "UNKNOWN";

        String sanitizedStepName = securityUtils.sanitizeStringInput(stepName);
        String sanitizedCorrelationId = securityUtils.sanitizeStringInput(correlationId);
        String sanitizedEventId = securityUtils.sanitizeStringInput(eventId);

        return databaseClient.sql(SQL_INSERT_STEP_FAILURE)
                .bind("stepName", sanitizedStepName)
                .bind("orderId", orderId)
                .bind("correlationId", sanitizedCorrelationId)
                .bind("eventId", sanitizedEventId)
                .bind("errorMessage", sanitizedErrorMessage)
                .bind("errorType", sanitizedErrorType)
                .bind("errorCategory", sanitizedErrorCategory)
                .then()
                .transform(mono -> withTimeoutsAndRetries(mono, "recordStepFailure"))
                .onErrorResume(e -> {
                    log.error("Failed to record step failure for order {} step {}: {}",
                            orderId, sanitizedStepName, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * Registra un fallo completo de saga
     */
    public Mono<Void> recordSagaFailure(
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = 64, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @Size(max = 1024, message = "errorMessage must be less than {max} characters")
            String errorMessage,
            @Size(max = 128, message = "errorType must be less than {max} characters")
            String errorType,
            @Size(max = 64, message = "errorCategory must be less than {max} characters")
            String errorCategory) {

        return recordSagaFailure(orderId, correlationId, errorMessage, errorType, DeliveryMode.AT_LEAST_ONCE);
    }

    /**
     * Registra un fallo completo de saga con modo de entrega específico
     */
    public Mono<Void> recordSagaFailure(
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = 64, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @Size(max = 1024, message = "errorMessage must be less than {max} characters")
            String errorMessage,
            @Size(max = 128, message = "errorType must be less than {max} characters")
            String errorType,
            @NotNull(message = "deliveryMode cannot be null")
            DeliveryMode deliveryMode) {

        validationUtils.validateOrderId(orderId);
        validationUtils.validateCorrelationId(correlationId);
        validationUtils.validateDeliveryMode(deliveryMode);

        // Manejo de valores potencialmente nulos con valores por defecto
        String sanitizedErrorMessage = errorMessage != null ?
                Encode.forHtml(validationUtils.truncateIfNeeded(errorMessage, validationUtils.getMaxErrorMessageLength())) :
                "No error message provided";

        String sanitizedErrorType = errorType != null ?
                securityUtils.sanitizeStringInput(validationUtils.truncateIfNeeded(errorType, validationUtils.getMaxErrorTypeLength())) :
                "UNKNOWN_ERROR";

        String delivery = deliveryMode.name();
        String sanitizedCorrelationId = securityUtils.sanitizeStringInput(correlationId);

        return databaseClient.sql(SQL_INSERT_SAGA_FAILURE)
                .bind("orderId", orderId)
                .bind("correlationId", sanitizedCorrelationId)
                .bind("errorMessage", sanitizedErrorMessage)
                .bind("errorType", sanitizedErrorType)
                .bind("deliveryMode", delivery)
                .then()
                .transform(mono -> withTimeoutsAndRetries(mono, "recordSagaFailure"))
                .onErrorResume(e -> {
                    log.error("Failed to record saga failure for order {}: {}", orderId, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * Registra un fallo de compensación
     */
    public Mono<Void> insertCompensationLog(
            @NotNull(message = "stepName cannot be null")
            @NotBlank(message = "stepName cannot be empty")
            @Size(max = 64, message = "stepName must be less than {max} characters")
            String stepName,
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = 64, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = 64, message = "eventId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "status cannot be null")
            @NotBlank(message = "status cannot be empty")
            @Size(max = 64, message = "status must be less than {max} characters")
            OrderStatus status) {

        validationUtils.validateStepName(stepName);
        validationUtils.validateOrderId(orderId);
        validationUtils.validateCorrelationId(correlationId);
        validationUtils.validateEventId(eventId);


        String sanitizedStepName = securityUtils.sanitizeStringInput(stepName);
        String sanitizedCorrelationId = securityUtils.sanitizeStringInput(correlationId);
        String sanitizedEventId = securityUtils.sanitizeStringInput(eventId);
        String sanitizedStatus = validationUtils.validateStatus(status);

        return databaseClient.sql(SQL_INSERT_COMPENSATION_LOG)
                .bind("stepName", sanitizedStepName)
                .bind("orderId", orderId)
                .bind("correlationId", sanitizedCorrelationId)
                .bind("eventId", sanitizedEventId)
                .bind("status", sanitizedStatus)
                .then()
                .transform(mono -> withTimeoutsAndRetries(mono, "insertCompensationLog"))
                .onErrorResume(e -> {
                    log.error("Failed to log compensation status for order {}: {}", orderId, e.getMessage(), e);
                    return Mono.empty();
                });
    }
}