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

    // SQL Queries - Actualizadas para reflejar la nueva estructura de la tabla
    private static final String SQL_CLEAN_EXPIRED_LOCKS =
            "DELETE FROM transaction_locks WHERE expires_at < CURRENT_TIMESTAMP AND released = FALSE";

    // Ajuste para manejar la posible existencia de múltiples locks por resource_id
    private static final String SQL_ACQUIRE_LOCK =
            "INSERT INTO transaction_locks (resource_id, correlation_id, locked_at, expires_at, lock_uuid) " +
                    "VALUES (:resourceId, :correlationId, CURRENT_TIMESTAMP, :expiresAt, :lockUuid)";

    private static final String SQL_RELEASE_LOCK =
            "UPDATE transaction_locks SET released = TRUE " +
                    "WHERE resource_id = :resourceId AND correlation_id = :correlationId AND lock_uuid = :lockUuid";

    // Nuevo método para verificar si un recurso ya está bloqueado (con cualquier UUID)
    private static final String SQL_CHECK_RESOURCE_LOCKED =
            "SELECT COUNT(*) FROM transaction_locks " +
                    "WHERE resource_id = :resourceId AND released = FALSE AND expires_at > CURRENT_TIMESTAMP";

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

        // Generar un UUID único para este bloqueo
        String lockUuid = UUID.randomUUID().toString();

        // Sanitizar y validar todos los parámetros
        String sanitizedResourceId = securityUtils.sanitizeResourceId(resourceId);
        String sanitizedCorrelationId = securityUtils.sanitizeCorrelationId(correlationId);
        String sanitizedLockUuid = securityUtils.sanitizeLockUuid(lockUuid); // Usar el nuevo método

        // Primero, limpiar bloqueos expirados para evitar deadlocks
        return databaseClient.sql(SQL_CLEAN_EXPIRED_LOCKS)
                .then()
                // Opcional: verificar si el recurso ya está bloqueado (solo para logging)
                .then(databaseClient.sql(SQL_CHECK_RESOURCE_LOCKED)
                        .bind("resourceId", sanitizedResourceId)
                        .map(row -> row.get(0, Long.class))
                        .one()
                        .doOnNext(count -> {
                            if (count > 0) {
                                log.debug("Resource {} already has {} active locks, attempting to acquire another lock with UUID {}",
                                        sanitizedResourceId, count, sanitizedLockUuid);
                            }
                        })
                )
                // Intentar adquirir el bloqueo
                .then(databaseClient.sql(SQL_ACQUIRE_LOCK)
                        .bind("resourceId", sanitizedResourceId)
                        .bind("correlationId", sanitizedCorrelationId)
                        .bind("expiresAt", expiresAt)
                        .bind("lockUuid", sanitizedLockUuid)
                        .then()
                        .thenReturn(true)
                        .doOnSuccess(v -> log.info("Acquired lock for resource {} with correlation ID {} and lock UUID {}",
                                sanitizedResourceId, sanitizedCorrelationId, sanitizedLockUuid))
                        .contextWrite(ctx -> ctx.put("lockUuid", sanitizedLockUuid))) // Guardar UUID en el contexto
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(100))
                        .filter(throwable -> !(throwable instanceof DataIntegrityViolationException))
                        .maxBackoff(Duration.ofMillis(500)))
                .onErrorResume(DataIntegrityViolationException.class, e -> {
                    // Con la nueva estructura, esto solo ocurriría si intentamos insertar el mismo UUID para el mismo recurso
                    log.warn("Failed to acquire lock for resource {} with UUID {}: Lock with same UUID might already exist",
                            sanitizedResourceId, sanitizedLockUuid);
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

        String sanitizedResourceId = securityUtils.sanitizeResourceId(resourceId);
        String sanitizedCorrelationId = securityUtils.sanitizeCorrelationId(correlationId);

        return Mono.deferContextual(ctx -> {
                    // Obtener el UUID del contexto, si está disponible
                    String lockUuid = ctx.getOrDefault("lockUuid", "");
                    // Importante: sanitizar y validar también el UUID del contexto
                    String sanitizedLockUuid = lockUuid.isEmpty() ? lockUuid : securityUtils.sanitizeLockUuid(lockUuid);

                    return databaseClient.sql(SQL_RELEASE_LOCK)
                            .bind("resourceId", sanitizedResourceId)
                            .bind("correlationId", sanitizedCorrelationId)
                            .bind("lockUuid", sanitizedLockUuid)
                            .then()
                            .doOnSuccess(v -> log.info("Released lock for resource {} with correlation ID {} and lock UUID {}",
                                    sanitizedResourceId, sanitizedCorrelationId, sanitizedLockUuid));
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

    /**
     * Libera todos los bloqueos asociados a un ID de recurso específico
     * Útil para limpiar todos los bloqueos de un recurso independientemente del UUID
     */
    public Mono<Long> releaseAllLocksForResource(
            @NotNull(message = "resourceId cannot be null")
            @NotBlank(message = "resourceId cannot be empty")
            String resourceId) {

        validationUtils.validateResourceId(resourceId);
        String sanitizedResourceId = securityUtils.sanitizeResourceId(resourceId);

        return databaseClient.sql("UPDATE transaction_locks SET released = TRUE WHERE resource_id = :resourceId AND released = FALSE")
                .bind("resourceId", sanitizedResourceId)
                .fetch()
                .rowsUpdated()
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("Released {} locks for resource {}", count, sanitizedResourceId);
                    } else {
                        log.debug("No active locks found for resource {}", sanitizedResourceId);
                    }
                })
                .transform(mono -> withTimeoutsAndRetries(mono, "releaseAllLocksForResource"))
                .onErrorResume(e -> {
                    log.error("Error releasing all locks for resource {}: {}", sanitizedResourceId, e.getMessage());
                    return Mono.just(0L);
                });
    }
}