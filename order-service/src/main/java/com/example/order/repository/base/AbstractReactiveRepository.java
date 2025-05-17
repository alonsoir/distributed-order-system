package com.example.order.repository.base;

import com.example.order.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Clase base abstracta para repositorios reactivos
 * Proporciona funcionalidad común para operaciones de base de datos
 */
public abstract class AbstractReactiveRepository {
    protected static final Logger log = LoggerFactory.getLogger(AbstractReactiveRepository.class);

    // Constantes para timeouts y reintentos
    protected static final Duration DB_OPERATION_TIMEOUT = Duration.ofSeconds(5);
    protected static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
    protected static final Duration GLOBAL_TIMEOUT = Duration.ofSeconds(30);
    protected static final int MAX_DB_RETRIES = 3;
    protected static final Duration INITIAL_BACKOFF = Duration.ofMillis(100);
    protected static final Duration MAX_BACKOFF = Duration.ofSeconds(1);

    protected final DatabaseClient databaseClient;
    protected final TransactionalOperator transactionalOperator;
    protected final SecurityUtils securityUtils;
    protected final ValidationUtils validationUtils;

    protected AbstractReactiveRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = transactionalOperator;
        this.securityUtils = securityUtils;
        this.validationUtils = validationUtils;
    }

    /**
     * Aplica timeout y manejo de errores estándar a una operación de base de datos
     */
    protected <T> Mono<T> withTimeoutsAndRetries(Mono<T> mono, String operationName) {
        return mono
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec(operationName))
                .timeout(GLOBAL_TIMEOUT) // Timeout global después de reintentos
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Timeout executing " + operationName + ": " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                });
    }

    /**
     * Aplica gestión de transacciones, timeout y manejo de errores a un bloque transaccional
     */
    protected <T> Mono<T> withTransaction(Mono<T> mono, String operationName, String contextInfo) {
        return transactionalOperator.transactional(mono)
                .timeout(TRANSACTION_TIMEOUT)
                .doOnError(e -> log.error("Transaction failed for {}: {}", contextInfo, e.getMessage(), e))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(100))
                        .filter(throwable -> !(throwable instanceof InvalidParameterException))
                        .maxBackoff(Duration.ofMillis(500)))
                .onErrorMap(TimeoutException.class, e ->
                        new TransactionTimeoutException("Transaction timeout for " + contextInfo + ": " + e.getMessage(), e))
                .onErrorMap(e -> !(e instanceof ApplicationException), e ->
                        new TransactionException("Transaction failed for " + contextInfo + ": " + e.getMessage(), e));
    }

    /**
     * Crea una especificación de reintento para operaciones de base de datos
     */
    protected Retry createRetrySpec(String operation) {
        AtomicInteger retryCount = new AtomicInteger(0);

        return Retry.backoff(MAX_DB_RETRIES, INITIAL_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .filter(throwable ->
                        // No reintentar errores de validación o parámetros inválidos
                        !(throwable instanceof InvalidParameterException) &&
                                // No reintentar errores de timeout (ya se manejan separadamente)
                                !(throwable instanceof TimeoutException) &&
                                // No reintentar cuando el recurso no existe (OrderNotFoundException)
                                !(throwable instanceof OrderNotFoundException)
                )
                .doBeforeRetryAsync(retrySignal -> {
                    int attemptNumber = retryCount.incrementAndGet();
                    log.warn("Retrying database operation {} after error, attempt: {}/{}",
                            operation, attemptNumber, MAX_DB_RETRIES);
                    return Mono.empty();
                });
    }

    /**
     * Maneja errores de integridad de datos (como llaves duplicadas)
     */
    protected <T> Mono<T> handleIntegrityError(DataIntegrityViolationException e, String operation, T defaultValue) {
        log.info("{} already exists: {}", operation, e.getMessage());
        return Mono.just(defaultValue);
    }

    /**
     * Maneja errores generales de base de datos
     */
    protected <T> Mono<T> handleDatabaseError(Throwable e, String operation) {
        if (!(e instanceof ApplicationException)) {
            String errorMsg = "Error in " + operation + ": " + e.getMessage();
            log.error(errorMsg, e);
            throw new DatabaseException(errorMsg, e);
        }
        return Mono.error(e);
    }
}