package com.example.order.repository.transactions;

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
import reactor.util.retry.Retry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Repositorio para operaciones relacionadas con bloqueos de transacciones
 */
@Repository
@Validated
public class TransactionLockRepository extends AbstractReactiveRepository {

    // Constantes
    private static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 30;

    // SQL Queries
    private static final String SQL_CLEAN_EXPIRED_LOCKS =
            "DELETE FROM transaction_locks WHERE expires_at < CURRENT_TIMESTAMP AND released = FALSE";
    private static final String SQL_ACQUIRE_LOCK =
            "INSERT INTO transaction_locks (resource_id, correlation_id, locked_at, expires_at, lock_uuid) " +
                    "VALUES (:resourceId, :correlationId, CURRENT_TIMESTAMP, :expiresAt, :lockUuid)";
    private static final String SQL_RELEASE_LOCK =
            "UPDATE transaction_locks SET released = TRUE " +
                    "WHERE resource_id = :resourceId AND correlation_id = :correlationId AND lock_uuid = :lockUuid";

    public TransactionLockRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils) {
        super(databaseClient, transactionalOperator, securityUtils, validationUtils);
    }

    /**
     * Adquiere un bloqueo de transacción para un recurso
     */
    public Mono<Boolean> acquireTransactionLock(
            @NotNull(message = "resourceId cannot be null")
            @NotBlank(message = "resourceId cannot be empty")
            @Size(max = 100, message = "resourceId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "resourceId must contain only letters, numbers, dashes and underscores")
            String resourceId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = 64, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            int timeoutSeconds) {

        validationUtils.validateResourceId(resourceId);
        validationUtils.validateCorrelationId(correlationId);

        // Usar un timeout por defecto si el valor es negativo o cero
        int effectiveTimeout = timeoutSeconds <= 0 ? DEFAULT_LOCK_TIMEOUT_SECONDS : timeoutSeconds;

        // Calcular la hora de expiración del bloqueo
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(effectiveTimeout);

        // Generar un UUID único para este bloqueo (permite identificar y liberar solo los bloqueos propios)
        String lockUuid = UUID.randomUUID().toString();

        String sanitizedResourceId = securityUtils.sanitizeInput(resourceId);
        String sanitizedCorrelationId = securityUtils.sanitizeInput(correlationId);

        // Primero, limpiar bloqueos expirados para evitar deadlocks
        return databaseClient.sql(SQL_CLEAN_EXPIRED_LOCKS)
                .then()
                .then(databaseClient.sql(SQL_ACQUIRE_LOCK)
                        .bind("resourceId", sanitizedResourceId)
                        .bind("correlationId", sanitizedCorrelationId)
                        .bind("expiresAt", expiresAt)
                        .bind("lockUuid", lockUuid)
                        .then()
                        .thenReturn(true)
                        .doOnSuccess(v -> log.info("Acquired lock for resource {} with correlation ID {}",
                                sanitizedResourceId, sanitizedCorrelationId))
                        .contextWrite(ctx -> ctx.put("lockUuid", lockUuid))) // Guardar UUID en el contexto
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(100))
                        .filter(throwable -> !(throwable instanceof DataIntegrityViolationException))
                        .maxBackoff(Duration.ofMillis(500)))
                .onErrorResume(DataIntegrityViolationException.class, e -> {
                    log.warn("Failed to acquire lock for resource {}: Resource is already locked", sanitizedResourceId);
                    return Mono.just(false);
                })
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout acquiring lock: " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(e -> handleDatabaseError(e, "acquireTransactionLock"));
    }

    /**
     * Libera un bloqueo de transacción para un recurso
     */
    public Mono<Void> releaseTransactionLock(
            @NotNull(message = "resourceId cannot be null")
            @NotBlank(message = "resourceId cannot be empty")
            @Size(max = 100, message = "resourceId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "resourceId must contain only letters, numbers, dashes and underscores")
            String resourceId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = 64, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId) {

        validationUtils.validateResourceId(resourceId);
        validationUtils.validateCorrelationId(correlationId);

        String sanitizedResourceId = securityUtils.sanitizeInput(resourceId);
        String sanitizedCorrelationId = securityUtils.sanitizeInput(correlationId);

        return Mono.deferContextual(ctx -> {
                    // Obtener el UUID del contexto, si está disponible
                    String lockUuid = ctx.getOrDefault("lockUuid", "");

                    return databaseClient.sql(SQL_RELEASE_LOCK)
                            .bind("resourceId", sanitizedResourceId)
                            .bind("correlationId", sanitizedCorrelationId)
                            .bind("lockUuid", lockUuid)
                            .then()
                            .doOnSuccess(v -> log.info("Released lock for resource {} with correlation ID {}",
                                    sanitizedResourceId, sanitizedCorrelationId));
                })
                .transform(mono -> withTimeoutsAndRetries(mono, "releaseTransactionLock"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout releasing lock: " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(e -> {
                    if (!(e instanceof ApplicationException)) {
                        log.warn("Failed to release lock for resource {}: {}", sanitizedResourceId, e.getMessage());
                        // No propagamos el error para que no interrumpa el flujo principal
                        return Mono.empty();
                    }
                    return Mono.error(e);
                });
    }
}