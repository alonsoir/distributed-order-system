package com.example.order.repository.orders;

import com.example.order.domain.DeliveryMode;
import com.example.order.domain.Order;
import com.example.order.events.OrderEvent;
import com.example.order.exception.*;
import com.example.order.repository.base.AbstractReactiveRepository;
import com.example.order.repository.base.SecurityUtils;
import com.example.order.repository.base.ValidationUtils;
import com.example.order.repository.events.ProcessedEventRepository;
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
 * Repositorio para operaciones relacionadas con órdenes
 */
@Repository
@Validated
public class OrderRepository extends AbstractReactiveRepository {

    // SQL Queries
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
            "INSERT INTO order_status_history (order_id, status, correlation_id, timestamp) " +// OrderRepository.java (continuación)
                    "VALUES (:orderId, :status, :correlationId, CURRENT_TIMESTAMP)";

    private final ProcessedEventRepository processedEventRepository;

    public OrderRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils,
            ProcessedEventRepository processedEventRepository) {
        super(databaseClient, transactionalOperator, securityUtils, validationUtils);
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Busca una orden por su ID
     */
    public Mono<Order> findOrderById(@NotNull(message = "orderId cannot be null") Long orderId) {
        validationUtils.validateOrderId(orderId);

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
                .transform(mono -> withTimeoutsAndRetries(mono, "findOrderById"))
                .onErrorResume(TimeoutException.class, e -> {
                    String errorMsg = "Database timeout finding order by id: " + e.getMessage();
                    log.error(errorMsg, e);
                    throw new DatabaseTimeoutException(errorMsg, e);
                })
                .onErrorResume(e -> {
                    if (e instanceof OrderNotFoundException || e instanceof DatabaseTimeoutException) {
                        return Mono.error(e);
                    }
                    return handleDatabaseError(e, "findOrderById");
                });
    }

    /**
     * Guarda los datos de una nueva orden
     */
    public Mono<Void> saveOrderData(
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
            @NotNull(message = "event cannot be null") OrderEvent event) {

        return saveOrderData(orderId, correlationId, eventId, event, DeliveryMode.AT_LEAST_ONCE);
    }

    /**
     * Guarda los datos de una nueva orden con modo de entrega específico
     */
    public Mono<Void> saveOrderData(
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
            @NotNull(message = "event cannot be null") OrderEvent event,
            DeliveryMode deliveryMode) {

        validationUtils.validateOrderId(orderId);
        validationUtils.validateCorrelationId(correlationId);
        validationUtils.validateEventId(eventId);
        if (event == null) {
            return Mono.error(new InvalidParameterException("event cannot be null"));
        }

        // Crear una variable final con el valor correcto
        final DeliveryMode effectiveDeliveryMode = deliveryMode != null ? deliveryMode : DeliveryMode.AT_LEAST_ONCE;

        // Para AT_MOST_ONCE, primero verificamos y marcamos atómicamente
        if (effectiveDeliveryMode == DeliveryMode.AT_MOST_ONCE) {
            return processedEventRepository.checkAndMarkEventAsProcessed(eventId, effectiveDeliveryMode)
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
        String sanitizedCorrelationId = securityUtils.sanitizeInput(correlationId);
        String sanitizedEventId = securityUtils.sanitizeInput(eventId);

        // Cadena de operaciones dentro de una transacción
        Mono<Void> transactionMono = Mono.defer(() -> {
            // Paso 1: Insertar orden
            Mono<Void> insertOrderMono = databaseClient.sql(SQL_INSERT_ORDER)
                    .bind("id", orderId)
                    .bind("status", "pending")
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
                    ? processedEventRepository.markEventAsProcessed(eventId, deliveryMode)
                    : Mono.empty();

            // Ejecutar todo en secuencia dentro de la transacción
            return insertOrderMono
                    .then(insertOutboxMono)
                    .then(markProcessedMono);
        });

        // Ejecutar con transacción, timeout y gestión de errores
        return withTransaction(transactionMono, "saveOrderData", "order " + orderId);
    }

    /**
     * Actualiza el estado de una orden
     */
    public Mono<Order> updateOrderStatus(
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "status cannot be null")
            @NotBlank(message = "status cannot be empty")
            @Size(max = 64, message = "status must be less than {max} characters")
            String status,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = 64, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId) {

        validationUtils.validateOrderId(orderId);
        validationUtils.validateStatus(status);
        validationUtils.validateCorrelationId(correlationId);

        log.info("Updating order {} status to {}", orderId, status);

        String sanitizedStatus = securityUtils.sanitizeInput(status);
        String sanitizedCorrelationId = securityUtils.sanitizeInput(correlationId);

        // Ejecutar actualización con transacción
        Mono<Order> updateMono = Mono.defer(() -> {
            // Paso 1: Actualizar estado de la orden
            Mono<Void> updateStatusMono = databaseClient.sql(SQL_UPDATE_ORDER_STATUS)
                    .bind("status", sanitizedStatus)
                    .bind("id", orderId)
                    .then()
                    .doOnSuccess(v -> log.info("Updated order {} status to {}", orderId, sanitizedStatus));

            // Paso 2: Insertar registro en historial de estados
            Mono<Void> insertHistoryMono = insertStatusAuditLog(orderId, sanitizedStatus, sanitizedCorrelationId);

            // Ejecutar ambas operaciones y retornar la orden actualizada
            return updateStatusMono
                    .then(insertHistoryMono)
                    .thenReturn(new Order(orderId, sanitizedStatus, sanitizedCorrelationId));
        });

        return withTransaction(updateMono, "updateOrderStatus", "order " + orderId);
    }

    /**
     * Inserta un registro en el historial de estados de la orden
     */
    public Mono<Void> insertStatusAuditLog(
            @NotNull(message = "orderId cannot be null") Long orderId,
            @NotNull(message = "status cannot be null")
            @NotBlank(message = "status cannot be empty")
            @Size(max = 64, message = "status must be less than {max} characters")
            String status,
            @NotNull(message = "correlationId cannot be null")
            @NotBlank(message = "correlationId cannot be empty")
            @Size(max = 64, message = "correlationId must be less than {max} characters")
            @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "correlationId must contain only letters, numbers, dashes and underscores")
            String correlationId) {

        validationUtils.validateOrderId(orderId);
        validationUtils.validateStatus(status);
        validationUtils.validateCorrelationId(correlationId);

        String sanitizedStatus = securityUtils.sanitizeInput(status);
        String sanitizedCorrelationId = securityUtils.sanitizeInput(correlationId);

        return databaseClient.sql(SQL_INSERT_STATUS_HISTORY)
                .bind("orderId", orderId)
                .bind("status", sanitizedStatus)
                .bind("correlationId", sanitizedCorrelationId)
                .then()
                .transform(mono -> withTimeoutsAndRetries(mono, "insertStatusAuditLog"))
                .onErrorResume(e -> {
                    log.warn("Failed to insert status audit log for order {}: {}", orderId, e.getMessage());
                    // No propagamos el error para que no interrumpa el flujo principal
                    return Mono.empty();
                });
    }
}