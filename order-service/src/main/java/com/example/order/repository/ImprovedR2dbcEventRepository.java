package com.example.order.repository;

import java.util.concurrent.atomic.AtomicInteger;

import com.example.order.domain.OrderStatus;
import reactor.core.publisher.Mono;
import com.example.order.domain.DeliveryMode;
import com.example.order.domain.Order;
import com.example.order.events.OrderEvent;
import com.example.order.exception.*;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.validation.annotation.Validated;
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
 * @deprecated Esta implementación ha sido reemplazada por {@link CompositeEventRepository}
 * y sus componentes. Será eliminada en futuras versiones.
 */
@Deprecated(since = "2.0.0", forRemoval = true)
@Repository
@Validated
public class ImprovedR2dbcEventRepository implements EventRepository {
    private static final Logger log = LoggerFactory.getLogger(ImprovedR2dbcEventRepository.class);

    // Mejores prácticas para timeouts y reintentos
    private static final Duration DB_OPERATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration GLOBAL_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_DB_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(100);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(1);
    private static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 30;

    // Validación y sanitización de SQL
    private static final int MAX_EVENT_ID_LENGTH = 64;
    private static final int MAX_CORRELATION_ID_LENGTH = 64;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1024;
    private static final int MAX_ERROR_TYPE_LENGTH = 128;
    private static final int MAX_ERROR_CATEGORY_LENGTH = 64;
    private static final int MAX_STATUS_LENGTH = 64;
    private static final int MAX_OPERATION_LENGTH = 32;
    private static final int MAX_OUTCOME_LENGTH = 32;
    private static final int MAX_STEP_NAME_LENGTH = 64;

    // Patrones regex para validación
    private static final String ALPHANUMERIC_PATTERN = "^[a-zA-Z0-9\\-_]+$";
    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;

    // SQL Queries predefinidas para evitar construir SQL dinámicamente
    private static final String SQL_SELECT_EVENT_PROCESSED =
            "SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId";
    private static final String SQL_SELECT_EVENT_PROCESSED_WITH_DELIVERY_MODE =
            "SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId AND delivery_mode = :deliveryMode";
    private static final String SQL_INSERT_PROCESSED_EVENT =
            "INSERT INTO processed_events (event_id, processed_at) VALUES (:eventId, CURRENT_TIMESTAMP)";
    private static final String SQL_INSERT_PROCESSED_EVENT_WITH_DELIVERY_MODE =
            "INSERT INTO processed_events (event_id, processed_at, delivery_mode) VALUES (:eventId, CURRENT_TIMESTAMP, :deliveryMode)";
    private static final String SQL_SELECT_ORDER_BY_ID =
            "SELECT id, status, correlation_id FROM orders WHERE id = :id";
    private static final String SQL_INSERT_ORDER =
            "INSERT INTO orders (id, status, correlation_id, created_at, updated_at, delivery_mode) " +
                    "VALUES (:id, :status, :correlationId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, :deliveryMode)";
    private static final String SQL_INSERT_OUTBOX =
            "INSERT INTO outbox (event_type, correlation_id, event_id, payload, created_at, status, topic, delivery_mode) " +
                    "VALUES (:event_type, :correlationId, :eventId, :payload, CURRENT_TIMESTAMP, 'PENDING', 'orders', :deliveryMode)";
    private static final String SQL_UPDATE_ORDER_STATUS =
            "UPDATE orders SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id";
    private static final String SQL_INSERT_STATUS_HISTORY =
            "INSERT INTO order_status_history (order_id, status, correlation_id, timestamp) " +
                    "VALUES (:orderId, :status, :correlationId, CURRENT_TIMESTAMP)";
    private static final String SQL_INSERT_COMPENSATION_LOG =
            "INSERT INTO compensation_log (step_name, order_id, correlation_id, event_id, status, timestamp) " +
                    "VALUES (:stepName, :orderId, :correlationId, :eventId, :status, CURRENT_TIMESTAMP)";
    private static final String SQL_INSERT_STEP_FAILURE =
            "INSERT INTO saga_step_failures (step_name, order_id, correlation_id, event_id, " +
                    "error_message, error_type, error_category, timestamp) " +
                    "VALUES (:stepName, :orderId, :correlationId, :eventId, :errorMessage, :errorType, :errorCategory, CURRENT_TIMESTAMP)";
    private static final String SQL_INSERT_SAGA_FAILURE =
            "INSERT INTO saga_failures (order_id, correlation_id, error_message, error_type, " +
                    "delivery_mode, timestamp) VALUES (:orderId, :correlationId, :errorMessage, " +
                    ":errorType, :deliveryMode, CURRENT_TIMESTAMP)";
    private static final String SQL_INSERT_EVENT_HISTORY =
            "INSERT INTO event_history (event_id, correlation_id, order_id, event_type, " +
                    "operation, outcome, timestamp, delivery_mode) VALUES " +
                    "(:eventId, :correlationId, :orderId, :eventType, :operation, :outcome, CURRENT_TIMESTAMP, :deliveryMode)";
    private static final String SQL_CHECK_AND_MARK_PROCESSED =
            "INSERT INTO processed_events (event_id, processed_at, delivery_mode) " +
                    "SELECT :eventId, CURRENT_TIMESTAMP, :deliveryMode " +
                    "WHERE NOT EXISTS (SELECT 1 FROM processed_events WHERE event_id = :eventId AND delivery_mode = :deliveryMode)";
    private static final String SQL_CLEAN_EXPIRED_LOCKS =
            "DELETE FROM transaction_locks WHERE expires_at < CURRENT_TIMESTAMP AND released = FALSE";
    private static final String SQL_ACQUIRE_LOCK =
            "INSERT INTO transaction_locks (resource_id, correlation_id, locked_at, expires_at, lock_uuid) " +
                    "VALUES (:resourceId, :correlationId, CURRENT_TIMESTAMP, :expiresAt, :lockUuid)";
    private static final String SQL_RELEASE_LOCK =
            "UPDATE transaction_locks SET released = TRUE " +
                    "WHERE resource_id = :resourceId AND correlation_id = :correlationId AND lock_uuid = :lockUuid";

    public ImprovedR2dbcEventRepository(
            DatabaseClient databaseClient,
            @Qualifier("r2dbcTransactionalOperator") TransactionalOperator transactionalOperator) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = transactionalOperator;
    }

    /**
     * Verifica si un evento ya ha sido procesado
     * @param eventId ID del evento a verificar
     * @return true si el evento ya ha sido procesado, false en caso contrario
     * @throws InvalidParameterException si eventId es null o inválido
     * @throws DatabaseTimeoutException si la operación excede el timeout
     * @throws DatabaseException para otros errores de base de datos
     */
    @Override
    public Mono<Boolean> isEventProcessed(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId) {

        validateEventId(eventId);

        return databaseClient.sql(SQL_SELECT_EVENT_PROCESSED)
                .bind("eventId", sanitizeInput(eventId))
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
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("isEventProcessed"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout checking if event is processed: " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(DatabaseMappingException.class, e -> {
                    log.error("Database mapping error: {}", e.getMessage(), e);
                    return Mono.just(false);
                })
                .onErrorResume(e -> {
                    if (!(e instanceof ApplicationException)) {
                        String errorMsg = "Error checking if event is processed: " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseException(errorMsg, e);
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Verifica si un evento ya ha sido procesado con un modo de entrega específico
     * @param eventId ID del evento a verificar
     * @param deliveryMode modo de entrega
     * @return true si el evento ya ha sido procesado, false en caso contrario
     * @throws InvalidParameterException si eventId o deliveryMode son null o inválidos
     * @throws DatabaseTimeoutException si la operación excede el timeout
     * @throws DatabaseException para otros errores de base de datos
     */
    @Override
    public Mono<Boolean> isEventProcessed(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "deliveryMode cannot be null")
            DeliveryMode deliveryMode) {

        validateEventId(eventId);
        validateDeliveryMode(deliveryMode);

        return databaseClient.sql(SQL_SELECT_EVENT_PROCESSED_WITH_DELIVERY_MODE)
                .bind("eventId", sanitizeInput(eventId))
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
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("isEventProcessed"))
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
                .onErrorResume(e -> {
                    if (!(e instanceof ApplicationException)) {
                        String errorMsg = "Error checking if event is processed with delivery mode " +
                                deliveryMode + ": " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseException(errorMsg, e);
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Marca un evento como procesado
     * @param eventId ID del evento a marcar
     * @return Mono<Void> completado cuando la operación termina
     * @throws InvalidParameterException si eventId es null o inválido
     * @throws DatabaseTimeoutException si la operación excede el timeout
     * @throws DatabaseException para otros errores de base de datos
     */
    @Override
    public Mono<Void> markEventAsProcessed(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId) {

        validateEventId(eventId);

        return databaseClient.sql(SQL_INSERT_PROCESSED_EVENT)
                .bind("eventId", sanitizeInput(eventId))
                .then()
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("markEventAsProcessed"))
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
                .onErrorResume(e -> {
                    if (!(e instanceof ApplicationException)) {
                        String errorMsg = "Error marking event as processed: " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseException(errorMsg, e);
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Marca un evento como procesado con un modo de entrega específico
     * @param eventId ID del evento a marcar
     * @param deliveryMode modo de entrega
     * @return Mono<Void> completado cuando la operación termina
     * @throws InvalidParameterException si eventId o deliveryMode son null o inválidos
     * @throws DatabaseTimeoutException si la operación excede el timeout
     * @throws DatabaseException para otros errores de base de datos
     */
    @Override
    public Mono<Void> markEventAsProcessed(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "deliveryMode cannot be null")
            DeliveryMode deliveryMode) {

        validateEventId(eventId);
        validateDeliveryMode(deliveryMode);

        return databaseClient.sql(SQL_INSERT_PROCESSED_EVENT_WITH_DELIVERY_MODE)
                .bind("eventId", sanitizeInput(eventId))
                .bind("deliveryMode", deliveryMode.name())
                .then()
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("markEventAsProcessed"))
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
                .onErrorResume(e -> {
                    if (!(e instanceof ApplicationException)) {
                        String errorMsg = "Error marking event as processed with delivery mode " +
                                deliveryMode + ": " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseException(errorMsg, e);
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Implementación atómica de verificar y marcar como procesado para evitar race conditions
     * @param eventId ID del evento a verificar y marcar
     * @param deliveryMode modo de entrega
     * @return true si el evento fue marcado, false si ya estaba procesado
     * @throws InvalidParameterException si eventId o deliveryMode son null o inválidos
     * @throws DatabaseTimeoutException si la operación excede el timeout
     * @throws DatabaseException para otros errores de base de datos
     */
    @Override
    public Mono<Boolean> checkAndMarkEventAsProcessed(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "deliveryMode cannot be null")
            DeliveryMode deliveryMode) {

        validateEventId(eventId);
        validateDeliveryMode(deliveryMode);

        return databaseClient.sql(SQL_CHECK_AND_MARK_PROCESSED)
                .bind("eventId", sanitizeInput(eventId))
                .bind("deliveryMode", deliveryMode.name())
                .fetch()
                .rowsUpdated()
                .map(rowsUpdated -> rowsUpdated > 0) // true si se insertó, false si ya existía
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("checkAndMarkEventAsProcessed"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout in check-and-mark operation: " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(e -> {
                    if (!(e instanceof ApplicationException)) {
                        String errorMsg = "Error in check-and-mark operation: " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseException(errorMsg, e);
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Busca una orden por su ID
     * @param orderId ID de la orden a buscar
     * @return la orden encontrada
     * @throws InvalidParameterException si orderId es null
     * @throws OrderNotFoundException si la orden no existe
     * @throws DatabaseTimeoutException si la operación excede el timeout
     * @throws DatabaseException para otros errores de base de datos
     */
    @Override
    public Mono<Order> findOrderById(@NotNull(message = "orderId cannot be null") Long orderId) {
        if (orderId == null) {
            return Mono.error(new InvalidParameterException("orderId cannot be null"));
        }

        return databaseClient.sql(SQL_SELECT_ORDER_BY_ID)
                .bind("id", orderId)
                .map((row, metadata) -> {
                    try {
                        return new Order(
                                row.get("id", Long.class),
                                row.get("status", String.class),
                                row.get("correlation_id", String.class)
                        );
                    } catch (Exception e) {
                        String errorMsg = "Error mapping order row: " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseMappingException(errorMsg, e);
                    }
                })
                .one()
                .switchIfEmpty(Mono.error(new OrderNotFoundException("Order not found: " + orderId)))
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("findOrderById"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout finding order by id: " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(e -> {
                    if (e instanceof OrderNotFoundException || e instanceof DatabaseTimeoutException) {
                        return Mono.error(e);
                    }
                    if (!(e instanceof ApplicationException)) {
                        String errorMsg = "Error finding order by id: " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseException(errorMsg, e);
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Guarda los datos de una nueva orden
     * @param orderId ID de la orden
     * @param correlationId ID de correlación
     * @param eventId ID del evento
     * @param event evento de orden
     * @return Mono<Void> completado cuando la operación termina
     */
    @Override
    public Mono<Void> saveOrderData(
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "event cannot be null") OrderEvent event) {

        return saveOrderData(orderId, correlationId, eventId, event, DeliveryMode.AT_LEAST_ONCE);
    }

    /**
     * Guarda los datos de una nueva orden con modo de entrega específico
     * @param orderId ID de la orden
     * @param correlationId ID de correlación
     * @param eventId ID del evento
     * @param event evento de orden
     * @param deliveryMode modo de entrega
     * @return Mono<Void> completado cuando la operación termina
     * @throws InvalidParameterException si algún parámetro es null o inválido
     * @throws DatabaseTimeoutException si la operación excede el timeout
     * @throws TransactionException si falla la transacción
     * @throws DatabaseException para otros errores de base de datos
     */
    @Override
    public Mono<Void> saveOrderData(
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "event cannot be null") OrderEvent event,
            DeliveryMode deliveryMode) {

        if (orderId == null) {
            return Mono.error(new InvalidParameterException("orderId cannot be null"));
        }
        validateCorrelationId(correlationId);
        validateEventId(eventId);
        if (event == null) {
            return Mono.error(new InvalidParameterException("event cannot be null"));
        }

        // Crear una variable final con el valor correcto
        final DeliveryMode effectiveDeliveryMode = deliveryMode != null ? deliveryMode : DeliveryMode.AT_LEAST_ONCE;

        // Para AT_MOST_ONCE, primero verificamos y marcamos atómicamente
        if (effectiveDeliveryMode == DeliveryMode.AT_MOST_ONCE) {
            return checkAndMarkEventAsProcessed(eventId, effectiveDeliveryMode)
                    .flatMap(wasMarked -> {
                        if (!wasMarked) {
                            log.info("Event {} already processed (AT_MOST_ONCE), skipping", eventId);
                            return Mono.empty();
                        }
                        return saveOrderDataInternal(orderId, correlationId, eventId, event, effectiveDeliveryMode);
                    });
        }

        // Para AT_LEAST_ONCE, procedemos directamente
        return saveOrderDataInternal(orderId, correlationId, eventId, event, effectiveDeliveryMode);
    }

    /**
     * Implementación interna para guardar datos de orden dentro de una transacción
     */
    private Mono<Void> saveOrderDataInternal(
            Long orderId, String correlationId, String eventId, OrderEvent event, DeliveryMode deliveryMode) {

        // Sanitizar entradas
        String sanitizedCorrelationId = sanitizeInput(correlationId);
        String sanitizedEventId = sanitizeInput(eventId);

        // Cadena de operaciones dentro de una transacción
        Mono<Void> transactionMono = Mono.defer(() -> {
            // Paso 1: Insertar orden
            Mono<Void> insertOrderMono = databaseClient.sql(SQL_INSERT_ORDER)
                    .bind("id", orderId)
                    .bind("status", OrderStatus.ORDER_PENDING.getValue())
                    .bind("correlationId", sanitizedCorrelationId)
                    .bind("deliveryMode", deliveryMode.name())
                    .then()
                    .doOnSuccess(v -> log.info("Inserted order {} into orders table", orderId));

            // Paso 2: Insertar evento en outbox
            Mono<Void> insertOutboxMono = databaseClient.sql(SQL_INSERT_OUTBOX)
                    .bind("event_type", event.getType().name())
                    .bind("correlationId", sanitizedCorrelationId)
                    .bind("eventId", sanitizedEventId)
                    .bind("payload", event.toJson())
                    .bind("deliveryMode", deliveryMode.name())
                    .then()
                    .doOnSuccess(v -> log.info("Inserted outbox event for order {}", orderId));

            // Paso 3: Marcar evento como procesado (solo para AT_LEAST_ONCE, AT_MOST_ONCE ya lo habría marcado antes)
            Mono<Void> markProcessedMono = deliveryMode == DeliveryMode.AT_LEAST_ONCE
                    ? markEventAsProcessed(eventId, deliveryMode)
                    : Mono.empty();

            // Ejecutar todo en secuencia dentro de la transacción
            return insertOrderMono
                    .then(insertOutboxMono)
                    .then(markProcessedMono);
        });

        // Ejecutar con transacción, timeout y gestión de errores
        return transactionalOperator.transactional(transactionMono)
                .timeout(TRANSACTION_TIMEOUT)
                .doOnError(e -> log.error("Transaction failed for order {}: {}", orderId, e.getMessage(), e))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(100))
                        .filter(throwable -> !(throwable instanceof InvalidParameterException))
                        .maxBackoff(Duration.ofMillis(500)))
                .onErrorMap(TimeoutException.class, e ->
                        new TransactionTimeoutException("Transaction timeout for order " + orderId, e))
                .onErrorMap(e -> !(e instanceof ApplicationException), e ->
                        new TransactionException("Transaction failed for order " + orderId + ": " + e.getMessage(), e));
    }

    /**
     * Actualiza el estado de una orden
     * @param orderId ID de la orden
     * @param status nuevo estado
     * @param correlationId ID de correlación
     * @return la orden actualizada
     * @throws InvalidParameterException si algún parámetro es null o inválido
     * @throws DatabaseTimeoutException si la operación excede el timeout
     * @throws DatabaseException para otros errores de base de datos
     */
    @Override
    public Mono<Order> updateOrderStatus(
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "status cannot be null")
            @NotBlank(message = "status cannot be empty")
            @Size(max = MAX_STATUS_LENGTH, message = "status must be less than {max} characters")
            OrderStatus status,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId) {

        if (orderId == null) {
            return Mono.error(new InvalidParameterException("orderId cannot be null"));
        }
        validateStatus(String.valueOf(status));
        validateCorrelationId(correlationId);

        log.info("Updating order {} status to {}", orderId, status);

        String sanitizedStatus = sanitizeInput(String.valueOf(status));
        String sanitizedCorrelationId = sanitizeInput(correlationId);

        // Ejecutar actualización con transacción
        Mono<Order> updateMono = Mono.defer(() -> {
            // Paso 1: Actualizar estado de la orden
            Mono<Void> updateStatusMono = databaseClient.sql(SQL_UPDATE_ORDER_STATUS)
                    .bind("status", sanitizedStatus)
                    .bind("id", orderId)
                    .then()
                    .doOnSuccess(v -> log.info("Updated order {} status to {}", orderId, sanitizedStatus));

            // Paso 2: Insertar registro en historial de estados
            Mono<Void> insertHistoryMono = insertStatusAuditLog(orderId, status, sanitizedCorrelationId);

            // Ejecutar ambas operaciones y retornar la orden actualizada
            return updateStatusMono
                    .then(insertHistoryMono)
                    .thenReturn(new Order(orderId, sanitizedStatus, sanitizedCorrelationId));
        });

        return transactionalOperator.transactional(updateMono)
                .timeout(TRANSACTION_TIMEOUT)
                .retryWhen(createRetrySpec("updateOrderStatus"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout updating order status: " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(e -> {
                    if (!(e instanceof ApplicationException)) {
                        String errorMsg = "Error updating order status: " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseException(errorMsg, e);
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Inserta un registro en el historial de estados de la orden
     * @param orderId ID de la orden
     * @param status estado
     * @param correlationId ID de correlación
     * @return Mono<Void> completado cuando la operación termina
     * @throws InvalidParameterException si algún parámetro es null o inválido
     * @throws DatabaseException para errores de base de datos
     */
    @Override
    public Mono<Void> insertStatusAuditLog(
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "status cannot be null")
            @NotBlank(message = "status cannot be empty")
            @Size(max = MAX_STATUS_LENGTH, message = "status must be less than {max} characters")
            OrderStatus status,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId) {

        if (orderId == null) {
            return Mono.error(new InvalidParameterException("orderId cannot be null"));
        }
        validateStatus(String.valueOf(status));
        validateCorrelationId(correlationId);

        String sanitizedStatus = sanitizeInput(String.valueOf(status));
        String sanitizedCorrelationId = sanitizeInput(correlationId);

        return databaseClient.sql(SQL_INSERT_STATUS_HISTORY)
                .bind("orderId", orderId)
                .bind("status", sanitizedStatus)
                .bind("correlationId", sanitizedCorrelationId)
                .then()
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("insertStatusAuditLog"))
                .onErrorResume(e -> {
                    log.warn("Failed to insert status audit log for order {}: {}", orderId, e.getMessage());
                    // No propagamos el error para que no interrumpa el flujo principal
                    return Mono.empty();
                });
    }

    /**
     * Registra un fallo de compensación
     * @param stepName nombre del paso
     * @param orderId ID de la orden
     * @param correlationId ID de correlación
     * @param eventId ID del evento
     * @param status estado
     * @return Mono<Void> completado cuando la operación termina
     * @throws InvalidParameterException si algún parámetro es null o inválido
     * @throws DatabaseException para errores de base de datos
     */
    @Override
    public Mono<Void> insertCompensationLog(
            @NotNull(message = "stepName cannot be null")
            @NotBlank(message = "stepName cannot be empty")
            @Size(max = MAX_STEP_NAME_LENGTH, message = "stepName must be less than {max} characters")
            String stepName,
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "status cannot be null")
            @NotBlank(message = "status cannot be empty")
            @Size(max = MAX_STATUS_LENGTH, message = "status must be less than {max} characters")
            OrderStatus status) {

        validateStepName(stepName);
        if (orderId == null) {
            return Mono.error(new InvalidParameterException("orderId cannot be null"));
        }
        validateCorrelationId(correlationId);
        validateEventId(eventId);
        validateStatus(String.valueOf(status));

        String sanitizedStepName = sanitizeInput(stepName);
        String sanitizedCorrelationId = sanitizeInput(correlationId);
        String sanitizedEventId = sanitizeInput(eventId);
        String sanitizedStatus = sanitizeInput(String.valueOf(status));

        return databaseClient.sql(SQL_INSERT_COMPENSATION_LOG)
                .bind("stepName", sanitizedStepName)
                .bind("orderId", orderId)
                .bind("correlationId", sanitizedCorrelationId)
                .bind("eventId", sanitizedEventId)
                .bind("status", sanitizedStatus)
                .then()
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("insertCompensationLog"))
                .onErrorResume(e -> {
                    log.error("Failed to log compensation status for order {}: {}", orderId, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * Registra un fallo de paso en la saga
     * @param stepName nombre del paso
     * @param orderId ID de la orden
     * @param correlationId ID de correlación
     * @param eventId ID del evento
     * @param errorMessage mensaje de error
     * @param errorType tipo de error
     * @param errorCategory categoría de error
     * @return Mono<Void> completado cuando la operación termina
     * @throws InvalidParameterException si algún parámetro esencial es null o inválido
     * @throws DatabaseException para errores de base de datos
     */
    @Override
    public Mono<Void> recordStepFailure(
            @NotNull(message = "stepName cannot be null")
            @NotBlank(message = "stepName cannot be empty")
            @Size(max = MAX_STEP_NAME_LENGTH, message = "stepName must be less than {max} characters")
            String stepName,
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @Size(max = MAX_ERROR_MESSAGE_LENGTH, message = "errorMessage must be less than {max} characters")
            String errorMessage,
            @Size(max = MAX_ERROR_TYPE_LENGTH, message = "errorType must be less than {max} characters")
            String errorType,
            @Size(max = MAX_ERROR_CATEGORY_LENGTH, message = "errorCategory must be less than {max} characters")
            String errorCategory) {

        validateStepName(stepName);
        if (orderId == null) {
            return Mono.error(new InvalidParameterException("orderId cannot be null"));
        }
        validateCorrelationId(correlationId);
        validateEventId(eventId);

        // Manejo de valores potencialmente nulos con valores por defecto
        String sanitizedErrorMessage = errorMessage != null ?
                Encode.forHtml(truncateIfNeeded(errorMessage, MAX_ERROR_MESSAGE_LENGTH)) :
                "No error message provided";

        String sanitizedErrorType = errorType != null ?
                sanitizeInput(truncateIfNeeded(errorType, MAX_ERROR_TYPE_LENGTH)) :
                "UNKNOWN_ERROR";

        String sanitizedErrorCategory = errorCategory != null ?
                sanitizeInput(truncateIfNeeded(errorCategory, MAX_ERROR_CATEGORY_LENGTH)) :
                "UNKNOWN";

        String sanitizedStepName = sanitizeInput(stepName);
        String sanitizedCorrelationId = sanitizeInput(correlationId);
        String sanitizedEventId = sanitizeInput(eventId);

        return databaseClient.sql(SQL_INSERT_STEP_FAILURE)
                .bind("stepName", sanitizedStepName)
                .bind("orderId", orderId)
                .bind("correlationId", sanitizedCorrelationId)
                .bind("eventId", sanitizedEventId)
                .bind("errorMessage", sanitizedErrorMessage)
                .bind("errorType", sanitizedErrorType)
                .bind("errorCategory", sanitizedErrorCategory)
                .then()
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("recordStepFailure"))
                .onErrorResume(e -> {
                    log.error("Failed to record step failure for order {} step {}: {}",
                            orderId, sanitizedStepName, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * Registra un fallo completo de saga
     * @param orderId ID de la orden
     * @param correlationId ID de correlación
     * @param errorMessage mensaje de error
     * @param errorType tipo de error
     * @param errorCategory categoría de error
     * @return Mono<Void> completado cuando la operación termina
     */
    @Override
    public Mono<Void> recordSagaFailure(
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @Size(max = MAX_ERROR_MESSAGE_LENGTH, message = "errorMessage must be less than {max} characters")
            String errorMessage,
            @Size(max = MAX_ERROR_TYPE_LENGTH, message = "errorType must be less than {max} characters")
            String errorType,
            @Size(max = MAX_ERROR_CATEGORY_LENGTH, message = "errorCategory must be less than {max} characters")
            String errorCategory) {

        return recordSagaFailure(orderId, correlationId, errorMessage, errorType, DeliveryMode.AT_LEAST_ONCE);
    }

    /**
     * Registra un fallo completo de saga con modo de entrega específico
     * @param orderId ID de la orden
     * @param correlationId ID de correlación
     * @param errorMessage mensaje de error
     * @param errorType tipo de error
     * @param deliveryMode modo de entrega
     * @return Mono<Void> completado cuando la operación termina
     * @throws InvalidParameterException si algún parámetro esencial es null o inválido
     * @throws DatabaseException para errores de base de datos
     */
    @Override
    public Mono<Void> recordSagaFailure(
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @Size(max = MAX_ERROR_MESSAGE_LENGTH, message = "errorMessage must be less than {max} characters")
            String errorMessage,
            @Size(max = MAX_ERROR_TYPE_LENGTH, message = "errorType must be less than {max} characters")
            String errorType,
            @NotNull(message = "deliveryMode cannot be null")
            DeliveryMode deliveryMode) {

        if (orderId == null) {
            return Mono.error(new InvalidParameterException("orderId cannot be null"));
        }
        validateCorrelationId(correlationId);
        validateDeliveryMode(deliveryMode);

        // Manejo de valores potencialmente nulos con valores por defecto
        String sanitizedErrorMessage = errorMessage != null ?
                Encode.forHtml(truncateIfNeeded(errorMessage, MAX_ERROR_MESSAGE_LENGTH)) :
                "No error message provided";

        String sanitizedErrorType = errorType != null ?
                sanitizeInput(truncateIfNeeded(errorType, MAX_ERROR_TYPE_LENGTH)) :
                "UNKNOWN_ERROR";

        String delivery = deliveryMode.name();
        String sanitizedCorrelationId = sanitizeInput(correlationId);

        return databaseClient.sql(SQL_INSERT_SAGA_FAILURE)
                .bind("orderId", orderId)
                .bind("correlationId", sanitizedCorrelationId)
                .bind("errorMessage", sanitizedErrorMessage)
                .bind("errorType", sanitizedErrorType)
                .bind("deliveryMode", delivery)
                .then()
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("recordSagaFailure"))
                .onErrorResume(e -> {
                    log.error("Failed to record saga failure for order {}: {}", orderId, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * Guarda historial de eventos para auditoría
     * @param eventId ID del evento
     * @param correlationId ID de correlación
     * @param orderId ID de la orden
     * @param eventType tipo de evento
     * @param operation operación
     * @param outcome resultado
     * @return Mono<Void> completado cuando la operación termina
     */
    @Override
    public Mono<Void> saveEventHistory(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "eventType cannot be null")
            @NotBlank(message = "eventType cannot be empty")
            String eventType,
            @NotNull(message = "operation cannot be null")
            @NotBlank(message = "operation cannot be empty")
            @Size(max = MAX_OPERATION_LENGTH, message = "operation must be less than {max} characters")
            String operation,
            @NotNull(message = "outcome cannot be null")
            @NotBlank(message = "outcome cannot be empty")
            @Size(max = MAX_OUTCOME_LENGTH, message = "outcome must be less than {max} characters")
            String outcome) {

        return saveEventHistory(eventId, correlationId, orderId, eventType, operation, outcome, DeliveryMode.AT_LEAST_ONCE);
    }

    /**
     * Guarda historial de eventos para auditoría con modo de entrega específico
     * @param eventId ID del evento
     * @param correlationId ID de correlación
     * @param orderId ID de la orden
     * @param eventType tipo de evento
     * @param operation operación
     * @param outcome resultado
     * @param deliveryMode modo de entrega
     * @return Mono<Void> completado cuando la operación termina
     * @throws InvalidParameterException si algún parámetro esencial es null o inválido
     * @throws DatabaseException para errores de base de datos
     */
    @Override
    public Mono<Void> saveEventHistory(
            @NotNull(message = "eventId cannot be null")
            @NotBlank(message = "eventId cannot be empty")
            @Size(max = MAX_EVENT_ID_LENGTH, message = "eventId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "eventId must contain only letters, numbers, dashes and underscores")
            String eventId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "eventType cannot be null")
            @NotBlank(message = "eventType cannot be empty")
            String eventType,
            @NotNull(message = "operation cannot be null")
            @NotBlank(message = "operation cannot be empty")
            @Size(max = MAX_OPERATION_LENGTH, message = "operation must be less than {max} characters")
            String operation,
            @NotNull(message = "outcome cannot be null")
            @NotBlank(message = "outcome cannot be empty")
            @Size(max = MAX_OUTCOME_LENGTH, message = "outcome must be less than {max} characters")
            String outcome,
            DeliveryMode deliveryMode) {

        validateEventId(eventId);
        validateCorrelationId(correlationId);
        if (orderId == null) {
            return Mono.error(new InvalidParameterException("orderId cannot be null"));
        }
        if (eventType == null || eventType.isBlank()) {
            return Mono.error(new InvalidParameterException("eventType cannot be null or empty"));
        }
        validateOperation(operation);
        validateOutcome(outcome);

        String delivery = deliveryMode != null ? deliveryMode.name() : DeliveryMode.AT_LEAST_ONCE.name();
        String sanitizedEventId = sanitizeInput(eventId);
        String sanitizedCorrelationId = sanitizeInput(correlationId);
        String sanitizedEventType = sanitizeInput(eventType);
        String sanitizedOperation = sanitizeInput(operation);
        String sanitizedOutcome = sanitizeInput(outcome);

        return databaseClient.sql(SQL_INSERT_EVENT_HISTORY)
                .bind("eventId", sanitizedEventId)
                .bind("correlationId", sanitizedCorrelationId)
                .bind("orderId", orderId)
                .bind("eventType", sanitizedEventType)
                .bind("operation", sanitizedOperation)
                .bind("outcome", sanitizedOutcome)
                .bind("deliveryMode", delivery)
                .then()
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("saveEventHistory"))
                .onErrorResume(e -> {
                    log.warn("Failed to save event history for event {}: {}", sanitizedEventId, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Adquiere un bloqueo de transacción para un recurso
     * @param resourceId ID del recurso
     * @param correlationId ID de correlación
     * @param timeoutSeconds tiempo de expiración del bloqueo en segundos
     * @return true si se adquirió el bloqueo, false en caso contrario
     * @throws InvalidParameterException si resourceId o correlationId son null o inválidos
     * @throws DatabaseTimeoutException si la operación excede el timeout
     * @throws DatabaseException para otros errores de base de datos
     */
    @Override
    public Mono<Boolean> acquireTransactionLock(
            @NotNull(message = "resourceId cannot be null")
            @NotBlank(message = "resourceId cannot be empty")
            @Size(max = 100, message = "resourceId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "resourceId must contain only letters, numbers, dashes and underscores")
            String resourceId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId,
            int timeoutSeconds) {

        if (resourceId == null || resourceId.isBlank()) {
            return Mono.error(new InvalidParameterException("resourceId cannot be null or empty"));
        }
        validateCorrelationId(correlationId);

        // Usar un timeout por defecto si el valor es negativo o cero
        int effectiveTimeout = timeoutSeconds <= 0 ? DEFAULT_LOCK_TIMEOUT_SECONDS : timeoutSeconds;

        // Calcular la hora de expiración del bloqueo
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(effectiveTimeout);

        // Generar un UUID único para este bloqueo (permite identificar y liberar solo los bloqueos propios)
        String lockUuid = UUID.randomUUID().toString();

        String sanitizedResourceId = sanitizeInput(resourceId);
        String sanitizedCorrelationId = sanitizeInput(correlationId);

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
                .onErrorResume(e -> {
                    if (!(e instanceof ApplicationException)) {
                        String errorMsg = "Error acquiring lock: " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new DatabaseException(errorMsg, e);
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Libera un bloqueo de transacción para un recurso
     * @param resourceId ID del recurso
     * @param correlationId ID de correlación
     * @return Mono<Void> completado cuando la operación termina
     * @throws InvalidParameterException si resourceId o correlationId son null o inválidos
     * @throws DatabaseTimeoutException si la operación excede el timeout
     * @throws DatabaseException para otros errores de base de datos
     */
    @Override
    public Mono<Void> releaseTransactionLock(
            @NotNull(message = "resourceId cannot be null")
            @NotBlank(message = "resourceId cannot be empty")
            @Size(max = 100, message = "resourceId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "resourceId must contain only letters, numbers, dashes and underscores")
            String resourceId,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = MAX_CORRELATION_ID_LENGTH, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = ALPHANUMERIC_PATTERN, message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId) {

        if (resourceId == null || resourceId.isBlank()) {
            return Mono.error(new InvalidParameterException("resourceId cannot be null or empty"));
        }
        validateCorrelationId(correlationId);

        String sanitizedResourceId = sanitizeInput(resourceId);
        String sanitizedCorrelationId = sanitizeInput(correlationId);

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
                .timeout(DB_OPERATION_TIMEOUT)
                .retryWhen(createRetrySpec("releaseTransactionLock"))
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

    private Retry createRetrySpec(String operation) {
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
     * Aplica timeout y manejo de errores estándar a una operación de base de datos
     * @param mono operación a ejecutar
     * @param operationName nombre de la operación para logs
     * @param <T> tipo de retorno
     * @return la operación con timeout y manejo de errores
     */
    private <T> Mono<T> withTimeoutsAndRetries(Mono<T> mono, String operationName) {
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
     * Sanitiza la entrada para prevenir inyección SQL
     * @param input entrada a sanitizar
     * @return entrada sanitizada
     */
    private String sanitizeInput(String input) {
        if (input == null) {
            return null;
        }

        // 1. Eliminar caracteres potencialmente peligrosos para SQL
        String sanitized = input.replaceAll("[;'\"]", "");

        // 2. Usar OWASP Encoder para codificar HTML y SQL
        return Encode.forHtml(sanitized);
    }

    /**
     * Trunca una cadena si excede la longitud máxima
     * @param input cadena a truncar
     * @param maxLength longitud máxima
     * @return cadena truncada
     */
    private String truncateIfNeeded(String input, int maxLength) {
        if (input == null) {
            return null;
        }

        return input.length() > maxLength ? input.substring(0, maxLength) : input;
    }

    /**
     * Valida un ID de evento
     * @param eventId ID de evento a validar
     * @throws InvalidParameterException si el ID de evento es null, vacío o inválido
     */
    private void validateEventId(String eventId) {
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

    /**
     * Valida un ID de correlación
     * @param correlationId ID de correlación a validar
     * @throws InvalidParameterException si el ID de correlación es null, vacío o inválido
     */
    private void validateCorrelationId(String correlationId) {
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

    /**
     * Valida un modo de entrega
     * @param deliveryMode modo de entrega a validar
     * @throws InvalidParameterException si el modo de entrega es null
     */
    private void validateDeliveryMode(DeliveryMode deliveryMode) {
        if (deliveryMode == null) {
            throw new InvalidParameterException("deliveryMode cannot be null");
        }
    }

    /**
     * Valida un estado
     * @param status estado a validar
     * @throws InvalidParameterException si el estado es null, vacío o inválido
     */
    private void validateStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new InvalidParameterException("status cannot be null or empty");
        }
        if (status.length() > MAX_STATUS_LENGTH) {
            throw new InvalidParameterException("status must be less than " + MAX_STATUS_LENGTH + " characters");
        }
    }

    /**
     * Valida un nombre de paso
     * @param stepName nombre de paso a validar
     * @throws InvalidParameterException si el nombre de paso es null, vacío o inválido
     */
    private void validateStepName(String stepName) {
        if (stepName == null || stepName.isBlank()) {
            throw new InvalidParameterException("stepName cannot be null or empty");
        }
        if (stepName.length() > MAX_STEP_NAME_LENGTH) {
            throw new InvalidParameterException("stepName must be less than " + MAX_STEP_NAME_LENGTH + " characters");
        }
    }

    /**
     * Valida una operación
     * @param operation operación a validar
     * @throws InvalidParameterException si la operación es null, vacía o inválida
     */
    private void validateOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new InvalidParameterException("operation cannot be null or empty");
        }
        if (operation.length() > MAX_OPERATION_LENGTH) {
            throw new InvalidParameterException("operation must be less than " + MAX_OPERATION_LENGTH + " characters");
        }
    }

    /**
     * Valida un resultado
     * @param outcome resultado a validar
     * @throws InvalidParameterException si el resultado es null, vacío o inválido
     */
    private void validateOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            throw new InvalidParameterException("outcome cannot be null or empty");
        }
        if (outcome.length() > MAX_OUTCOME_LENGTH) {
            throw new InvalidParameterException("outcome must be less than " + MAX_OUTCOME_LENGTH + " characters");
        }
    }
}