package com.example.order.repository.events;

import com.example.order.domain.DeliveryMode;
import com.example.order.exception.*;
import com.example.order.repository.base.AbstractReactiveRepository;
import com.example.order.repository.base.SecurityUtils;
import com.example.order.repository.base.ValidationUtils;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Repositorio para operaciones relacionadas con eventos procesados
 */
@Repository
@Validated
public class ProcessedEventRepository extends AbstractReactiveRepository {

    // SQL Queries
    private static final String SQL_SELECT_EVENT_PROCESSED =
            "SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId";
    private static final String SQL_SELECT_EVENT_PROCESSED_WITH_DELIVERY_MODE =
            "SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId AND delivery_mode = :deliveryMode";
    private static final String SQL_INSERT_PROCESSED_EVENT =
            "INSERT INTO processed_events (event_id, processed_at) VALUES (:eventId, CURRENT_TIMESTAMP)";
    private static final String SQL_INSERT_PROCESSED_EVENT_WITH_DELIVERY_MODE =
            "INSERT INTO processed_events (event_id, processed_at, delivery_mode) VALUES (:eventId, CURRENT_TIMESTAMP, :deliveryMode)";
    private static final String SQL_CHECK_AND_MARK_PROCESSED =
            "INSERT INTO processed_events (event_id, processed_at, delivery_mode) " +
                    "SELECT :eventId, CURRENT_TIMESTAMP, :deliveryMode " +
                    "WHERE NOT EXISTS (SELECT 1 FROM processed_events WHERE event_id = :eventId AND delivery_mode = :deliveryMode)";

    public ProcessedEventRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils) {
        super(databaseClient, transactionalOperator, securityUtils, validationUtils);
    }

    /**
     * Verifica si un evento ya ha sido procesado
     */
    public Mono<Boolean> isEventProcessed(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = 64, message = "eventId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId) {

        validationUtils.validateEventId(eventId);
        String sanitizedEventId = securityUtils.sanitizeInput(eventId);

        return databaseClient.sql(SQL_SELECT_EVENT_PROCESSED)
                .bind("eventId", sanitizedEventId)
                .map((row, metadata) -> {
                    try {
                        Integer count = row.get(0, Integer.class);
                        return count != null && count > 0;
                    } catch (Exception e) {
                        String errorMsg = "Error mapping database row: " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseMappingException(errorMsg, e);
                    }
                })
                .one()
                .defaultIfEmpty(false)
                .transform(mono -> withTimeoutsAndRetries(mono, "isEventProcessed"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout checking if event is processed: " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(DatabaseMappingException.class, e -> {
                    log.error("Database mapping error: {}", e.getMessage(), e);
                    return Mono.just(false);
                })
                .onErrorResume(e -> handleDatabaseError(e, "isEventProcessed"));
    }

    /**
     * Verifica si un evento ya ha sido procesado con un modo de entrega específico
     */
    public Mono<Boolean> isEventProcessed(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = 64, message = "eventId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "deliveryMode cannot be null")
            DeliveryMode deliveryMode) {

        validationUtils.validateEventId(eventId);
        validationUtils.validateDeliveryMode(deliveryMode);

        String sanitizedEventId = securityUtils.sanitizeInput(eventId);

        return databaseClient.sql(SQL_SELECT_EVENT_PROCESSED_WITH_DELIVERY_MODE)
                .bind("eventId", sanitizedEventId)
                .bind("deliveryMode", deliveryMode.name())
                .map((row, metadata) -> {
                    try {
                        Integer count = row.get(0, Integer.class);
                        return count != null && count > 0;
                    } catch (Exception e) {
                        String errorMsg = "Error mapping database row: " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseMappingException(errorMsg, e);
                    }
                })
                .one()
                .defaultIfEmpty(false)
                .transform(mono -> withTimeoutsAndRetries(mono, "isEventProcessed"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout checking if event is processed with delivery mode " +
                            deliveryMode + ": " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(DatabaseMappingException.class, e -> {
                    log.error("Database mapping error: {}", e.getMessage(), e);
                    return Mono.just(false);
                })
                .onErrorResume(e -> handleDatabaseError(e, "isEventProcessed with delivery mode"));
    }

    /**
     * Marca un evento como procesado
     */
    public Mono<Void> markEventAsProcessed(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = 64, message = "eventId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId) {

        validationUtils.validateEventId(eventId);
        String sanitizedEventId = securityUtils.sanitizeInput(eventId);

        return databaseClient.sql(SQL_INSERT_PROCESSED_EVENT)
                .bind("eventId", sanitizedEventId)
                .then()
                .transform(mono -> withTimeoutsAndRetries(mono, "markEventAsProcessed"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout marking event as processed: " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(DataIntegrityViolationException.class, e -> {
                    // Si falla por duplicate key, el evento ya estaba procesado
                    log.info("Event {} was already marked as processed", eventId);
                    return Mono.empty();
                })
                .onErrorResume(e -> handleDatabaseError(e, "markEventAsProcessed"));
    }

    /**
     * Marca un evento como procesado con un modo de entrega específico
     */
    public Mono<Void> markEventAsProcessed(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = 64, message = "eventId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "deliveryMode cannot be null")
            DeliveryMode deliveryMode) {

        validationUtils.validateEventId(eventId);
        validationUtils.validateDeliveryMode(deliveryMode);

        String sanitizedEventId = securityUtils.sanitizeInput(eventId);

        return databaseClient.sql(SQL_INSERT_PROCESSED_EVENT_WITH_DELIVERY_MODE)
                .bind("eventId", sanitizedEventId)
                .bind("deliveryMode", deliveryMode.name())
                .then()
                .transform(mono -> withTimeoutsAndRetries(mono, "markEventAsProcessed"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout marking event as processed with delivery mode " +
                            deliveryMode + ": " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(DataIntegrityViolationException.class, e -> {
                    // Si falla por duplicate key, el evento ya estaba procesado
                    log.info("Event {} was already marked as processed with delivery mode {}", eventId, deliveryMode);
                    return Mono.empty();
                })
                .onErrorResume(e -> handleDatabaseError(e, "markEventAsProcessed with delivery mode"));
    }

    /**
     * Implementación atómica de verificar y marcar como procesado para evitar race conditions
     */
    public Mono<Boolean> checkAndMarkEventAsProcessed(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = 64, message = "eventId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "deliveryMode cannot be null")
            DeliveryMode deliveryMode) {

        validationUtils.validateEventId(eventId);
        validationUtils.validateDeliveryMode(deliveryMode);

        String sanitizedEventId = securityUtils.sanitizeInput(eventId);

        return databaseClient.sql(SQL_CHECK_AND_MARK_PROCESSED)
                .bind("eventId", sanitizedEventId)
                .bind("deliveryMode", deliveryMode.name())
                .fetch()
                .rowsUpdated()
                .map(rowsUpdated -> rowsUpdated > 0) // true si se insertó, false si ya existía
                .transform(mono -> withTimeoutsAndRetries(mono, "checkAndMarkEventAsProcessed"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout in check-and-mark operation: " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(e -> handleDatabaseError(e, "checkAndMarkEventAsProcessed"));
    }
}