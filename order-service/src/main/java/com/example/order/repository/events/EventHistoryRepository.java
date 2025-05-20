package com.example.order.repository.events;

import com.example.order.domain.DeliveryMode;
import com.example.order.exception.*;
import com.example.order.repository.base.AbstractReactiveRepository;
import com.example.order.repository.base.SecurityUtils;
import com.example.order.repository.base.ValidationUtils;
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
 * Repositorio para operaciones relacionadas con el historial de eventos
 */
@Repository
@Validated
public class EventHistoryRepository extends AbstractReactiveRepository {

    // SQL Queries
    private static final String SQL_INSERT_EVENT_HISTORY =
            "INSERT INTO event_history (event_id, correlation_id, order_id, event_type, " +
                    "operation, outcome, timestamp, delivery_mode) VALUES " +
                    "(:eventId, :correlationId, :orderId, :eventType, :operation, :outcome, CURRENT_TIMESTAMP, :deliveryMode)";

    public EventHistoryRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils) {
        super(databaseClient, transactionalOperator, securityUtils, validationUtils);
    }

    /**
     * Guarda historial de eventos para auditoría
     */
    public Mono<Void> saveEventHistory(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = 64, message = "eventId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = 64, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "eventType cannot be null")
            @NotBlank(message = "eventType cannot be empty")
            String eventType,
            @NotNull(message = "operation cannot be null")
            @NotBlank(message = "operation cannot be empty")
            @Size(max = 32, message = "operation must be less than {max} characters")
            String operation,
            @NotNull(message = "outcome cannot be null")
            @NotBlank(message = "outcome cannot be empty")
            @Size(max = 32, message = "outcome must be less than {max} characters")
            String outcome) {

        return saveEventHistory(eventId, correlationId, orderId, eventType, operation, outcome, DeliveryMode.AT_LEAST_ONCE);
    }

    /**
     * Guarda historial de eventos para auditoría con modo de entrega específico
     */
    public Mono<Void> saveEventHistory(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = 64, message = "eventId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = 64, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "eventType cannot be null")
            @NotBlank(message = "eventType cannot be empty")
            String eventType,
            @NotNull(message = "operation cannot be null")
            @NotBlank(message = "operation cannot be empty")
            @Size(max = 32, message = "operation must be less than {max} characters")
            String operation,
            @NotNull(message = "outcome cannot be null")
            @NotBlank(message = "outcome cannot be empty")
            @Size(max = 32, message = "outcome must be less than {max} characters")
            String outcome,
            DeliveryMode deliveryMode) {

        validationUtils.validateEventId(eventId);
        validationUtils.validateCorrelationId(correlationId);
        validationUtils.validateOrderId(orderId);
        validationUtils.validateEventType(eventType);
        validationUtils.validateOperation(operation);
        validationUtils.validateOutcome(outcome);

        String delivery = deliveryMode != null ? deliveryMode.name() : DeliveryMode.AT_LEAST_ONCE.name();
        String sanitizedEventId = securityUtils.sanitizeStringInput(eventId);
        String sanitizedCorrelationId = securityUtils.sanitizeStringInput(correlationId);
        String sanitizedEventType = securityUtils.sanitizeStringInput(eventType);
        String sanitizedOperation = securityUtils.sanitizeStringInput(operation);
        String sanitizedOutcome = securityUtils.sanitizeStringInput(outcome);

        return databaseClient.sql(SQL_INSERT_EVENT_HISTORY)
                .bind("eventId", sanitizedEventId)
                .bind("correlationId", sanitizedCorrelationId)
                .bind("orderId", orderId)
                .bind("eventType", sanitizedEventType)
                .bind("operation", sanitizedOperation)
                .bind("outcome", sanitizedOutcome)
                .bind("deliveryMode", delivery)
                .then()
                .transform(mono -> withTimeoutsAndRetries(mono, "saveEventHistory"))
                .onErrorResume(e -> {
                    log.warn("Failed to save event history for event {}: {}", sanitizedEventId, e.getMessage());
                    return Mono.empty();
                });
    }
}