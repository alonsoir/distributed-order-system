package com.example.order.repository;

import com.example.order.domain.DeliveryMode;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderEventType;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pruebas de integración para ImprovedR2dbcEventRepository utilizando MySQL en un contenedor
 */
@SpringBootTest
@Testcontainers
@Deprecated(since = "2.0.0", forRemoval = true)

public class ImprovedR2dbcEventRepositoryIntegrationTest {

    // Container MySQL para pruebas
    @Container
    public static MySQLContainer<?> mySQLContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format("r2dbc:mysql://%s:%d/%s",
                mySQLContainer.getHost(),
                mySQLContainer.getFirstMappedPort(),
                mySQLContainer.getDatabaseName()));
        registry.add("spring.r2dbc.username", mySQLContainer::getUsername);
        registry.add("spring.r2dbc.password", mySQLContainer::getPassword);
    }

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private ConnectionFactory connectionFactory;

    private ImprovedR2dbcEventRepository repository;
    private TransactionalOperator transactionalOperator;

    @BeforeEach
    void setUp() {
        R2dbcTransactionManager transactionManager = new R2dbcTransactionManager(connectionFactory);
        transactionalOperator = TransactionalOperator.create(transactionManager);
        repository = new ImprovedR2dbcEventRepository(databaseClient, transactionalOperator);

        // Crear tablas necesarias para las pruebas
        Mono<Void> createTables = databaseClient.sql(
                        "CREATE TABLE IF NOT EXISTS processed_events (" +
                                "event_id VARCHAR(36) PRIMARY KEY, " +
                                "processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE'" +
                                ")")
                .then()
                .then(databaseClient.sql(
                        "CREATE TABLE IF NOT EXISTS orders (" +
                                "id BIGINT PRIMARY KEY, " +
                                "status VARCHAR(50) NOT NULL, " +
                                "correlation_id VARCHAR(36) NOT NULL, " +
                                "external_reference VARCHAR(36), " +
                                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "version INT DEFAULT 0, " +
                                "delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE'" +
                                ")").then())
                .then(databaseClient.sql(
                        "CREATE TABLE IF NOT EXISTS outbox (" +
                                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                                "event_type VARCHAR(50) NOT NULL, " +
                                "correlation_id VARCHAR(36) NOT NULL, " +
                                "event_id VARCHAR(36) NOT NULL, " +
                                "payload TEXT NOT NULL, " +
                                "topic VARCHAR(255) NOT NULL DEFAULT 'orders', " + // Valor por defecto para topic
                                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "status VARCHAR(20) DEFAULT 'PENDING', " +
                                "published_at TIMESTAMP NULL, " +
                                "delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE', " +
                                "CONSTRAINT uk_outbox_event_id UNIQUE (event_id)" +
                                ")").then())
                .then(databaseClient.sql(
                        "CREATE TABLE IF NOT EXISTS order_status_history (" +
                                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                                "order_id BIGINT NOT NULL, " +
                                "status VARCHAR(50) NOT NULL, " +
                                "correlation_id VARCHAR(36) NOT NULL, " +
                                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                                ")").then())
                .then(databaseClient.sql(
                        "CREATE TABLE IF NOT EXISTS compensation_log (" +
                                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                                "step_name VARCHAR(50) NOT NULL, " +
                                "order_id BIGINT NOT NULL, " +
                                "correlation_id VARCHAR(36) NOT NULL, " +
                                "event_id VARCHAR(36) NOT NULL, " +
                                "status VARCHAR(20) NOT NULL, " +
                                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                                ")").then())
                .then(databaseClient.sql(
                        "CREATE TABLE IF NOT EXISTS saga_failures (" +
                                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                                "order_id BIGINT NOT NULL, " +
                                "correlation_id VARCHAR(36) NOT NULL, " +
                                "error_message TEXT NOT NULL, " +
                                "error_type VARCHAR(100) NOT NULL, " +
                                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "requires_attention BOOLEAN DEFAULT FALSE, " +
                                "delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE'" +
                                ")").then())
                .then(databaseClient.sql(
                        "CREATE TABLE IF NOT EXISTS saga_step_failures (" +
                                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                                "step_name VARCHAR(50) NOT NULL, " +
                                "order_id BIGINT NOT NULL, " +
                                "correlation_id VARCHAR(36) NOT NULL, " +
                                "event_id VARCHAR(36) NOT NULL, " +
                                "error_message TEXT NOT NULL, " +
                                "error_type VARCHAR(100) NOT NULL, " +
                                "error_category VARCHAR(50) DEFAULT 'UNKNOWN', " +
                                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                                ")").then())
                .then(databaseClient.sql(
                        "CREATE TABLE IF NOT EXISTS event_history (" +
                                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                                "event_id VARCHAR(36) NOT NULL, " +
                                "correlation_id VARCHAR(36) NOT NULL, " +
                                "order_id BIGINT NOT NULL, " +
                                "event_type VARCHAR(50) NOT NULL, " +
                                "operation VARCHAR(50) NOT NULL, " +
                                "outcome VARCHAR(50) NOT NULL, " +
                                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE'" +
                                ")").then())
                .then(databaseClient.sql(
                        "CREATE TABLE IF NOT EXISTS transaction_locks (" +
                                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                                "resource_id VARCHAR(100) NOT NULL, " +
                                "correlation_id VARCHAR(36) NOT NULL, " +
                                "locked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "expires_at TIMESTAMP NULL, " +
                                "released BOOLEAN DEFAULT FALSE, " +
                                "CONSTRAINT unique_resource UNIQUE (resource_id)" +
                                ")").then());

        // Ejecutar la creación de tablas
        StepVerifier.create(createTables)
                .verifyComplete();
    }

    @Test
    void testAtLeastOnceEventProcessing() {
        // Generar IDs únicos para la prueba
        String eventId = UUID.randomUUID().toString();
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        // Verificar que el evento no está procesado inicialmente
        StepVerifier.create(repository.isEventProcessed(eventId, deliveryMode))
                .expectNext(false)
                .verifyComplete();

        // Marcar el evento como procesado
        StepVerifier.create(repository.markEventAsProcessed(eventId, deliveryMode))
                .verifyComplete();

        // Verificar que el evento ahora está marcado como procesado
        StepVerifier.create(repository.isEventProcessed(eventId, deliveryMode))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void testAtMostOnceEventProcessing() {
        // Generar IDs únicos para la prueba
        String eventId = UUID.randomUUID().toString();
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        // Verificar que el evento no está procesado inicialmente
        StepVerifier.create(repository.isEventProcessed(eventId, deliveryMode))
                .expectNext(false)
                .verifyComplete();

        // Marcar el evento como procesado
        StepVerifier.create(repository.markEventAsProcessed(eventId, deliveryMode))
                .verifyComplete();

        // Verificar que el evento ahora está marcado como procesado
        StepVerifier.create(repository.isEventProcessed(eventId, deliveryMode))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void testSaveAndFindOrder_AtLeastOnce() {
        // Generar datos para la prueba
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        // Crear un OrderEvent para la prueba
        OrderEvent orderEvent = new TestOrderEvent(orderId, correlationId, eventId, OrderEventType.ORDER_CREATED);

        // Guardar los datos de la orden
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, orderEvent, deliveryMode))
                .verifyComplete();

        // Verificar que la orden se guardó correctamente
        StepVerifier.create(repository.findOrderById(orderId))
                .expectNextMatches(order ->
                        order.getId().equals(orderId) &&
                                "pending".equals(order.getStatus()) &&
                                correlationId.equals(order.getCorrelationId()))
                .verifyComplete();

        // Verificar que el evento está marcado como procesado
        StepVerifier.create(repository.isEventProcessed(eventId, deliveryMode))
                .expectNext(true)
                .verifyComplete();

        // Verificar que se registró en el outbox
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM outbox WHERE event_id = :eventId AND delivery_mode = :deliveryMode")
                        .bind("eventId", eventId)
                        .bind("deliveryMode", deliveryMode.name())
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNextMatches(count -> count > 0)
                .verifyComplete();
    }

    @Test
    void testSaveAndFindOrder_AtMostOnce() {
        // Generar datos para la prueba
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        // Crear un OrderEvent para la prueba
        OrderEvent orderEvent = new TestOrderEvent(orderId, correlationId, eventId, OrderEventType.ORDER_CREATED);

        // Guardar los datos de la orden
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, orderEvent, deliveryMode))
                .verifyComplete();

        // Verificar que la orden se guardó correctamente
        StepVerifier.create(repository.findOrderById(orderId))
                .expectNextMatches(order ->
                        order.getId().equals(orderId) &&
                                "pending".equals(order.getStatus()) &&
                                correlationId.equals(order.getCorrelationId()))
                .verifyComplete();

        // Verificar que el evento está marcado como procesado
        StepVerifier.create(repository.isEventProcessed(eventId, deliveryMode))
                .expectNext(true)
                .verifyComplete();

        // Verificar que se registró en el outbox con el modo de entrega correcto
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM outbox WHERE event_id = :eventId AND delivery_mode = :deliveryMode")
                        .bind("eventId", eventId)
                        .bind("deliveryMode", deliveryMode.name())
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNextMatches(count -> count > 0)
                .verifyComplete();
    }

    @Test
    void testUpdateOrderStatus() {
        // Generar datos para la prueba
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        // Crear un OrderEvent para la prueba
        OrderEvent orderEvent = new TestOrderEvent(orderId, correlationId, eventId, OrderEventType.ORDER_CREATED);

        // Guardar los datos de la orden
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, orderEvent, deliveryMode))
                .verifyComplete();

        // Actualizar el estado de la orden
        String newStatus = "completed";
        StepVerifier.create(repository.updateOrderStatus(orderId, newStatus, correlationId))
                .expectNextMatches(order ->
                        order.getId().equals(orderId) &&
                                newStatus.equals(order.getStatus()) &&
                                correlationId.equals(order.getCorrelationId()))
                .verifyComplete();

        // Verificar que el estado se actualizó correctamente
        StepVerifier.create(repository.findOrderById(orderId))
                .expectNextMatches(order -> newStatus.equals(order.getStatus()))
                .verifyComplete();

        // Verificar que se registró en el historial de estados
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM order_status_history WHERE order_id = :orderId AND status = :status")
                        .bind("orderId", orderId)
                        .bind("status", newStatus)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNextMatches(count -> count > 0)
                .verifyComplete();
    }

    @Test
    void testRecordStepFailure() {
        // Generar datos para la prueba
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String stepName = "payment_processing";
        String errorMessage = "Payment service unavailable";
        String errorType = "ServiceUnavailableException";
        String errorCategory = "EXTERNAL_SERVICE_ERROR";

        // Registrar el fallo del paso
        StepVerifier.create(repository.recordStepFailure(
                        stepName, orderId, correlationId, eventId, errorMessage, errorType, errorCategory))
                .verifyComplete();

        // Verificar que se registró el fallo
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM saga_step_failures WHERE step_name = :stepName AND order_id = :orderId")
                        .bind("stepName", stepName)
                        .bind("orderId", orderId)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNextMatches(count -> count > 0)
                .verifyComplete();
    }

    @Test
    void testTransactionLocks() {
        // Generar datos para la prueba
        String resourceId = "order-" + System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        int lockTimeoutSeconds = 60;

        // Adquirir un bloqueo
        StepVerifier.create(repository.acquireTransactionLock(resourceId, correlationId, lockTimeoutSeconds))
                .expectNext(true)
                .verifyComplete();

        // Intentar adquirir el mismo bloqueo de nuevo (debería fallar)
        StepVerifier.create(repository.acquireTransactionLock(resourceId, correlationId, lockTimeoutSeconds))
                .expectNext(false)
                .verifyComplete();

        // Liberar el bloqueo
        StepVerifier.create(repository.releaseTransactionLock(resourceId, correlationId))
                .verifyComplete();

        // Verificar que el bloqueo se liberó correctamente
        StepVerifier.create(databaseClient.sql(
                                "SELECT released FROM transaction_locks WHERE resource_id = :resourceId")
                        .bind("resourceId", resourceId)
                        .map((row, metadata) -> row.get("released", Boolean.class))
                        .one())
                .expectNext(true)
                .verifyComplete();

        // Después de liberar, debería poder adquirir el bloqueo nuevamente
        // Nota: En una implementación real, probablemente necesitarías limpiar los bloqueos viejos
        // o tener una lógica más sofisticada para detectar bloqueos liberados.
    }

    @Test
    void testRecordSagaFailure() {
        // Generar datos para la prueba
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String errorMessage = "Payment service unavailable";
        String errorType = "ServiceUnavailableException";
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        // Registrar un fallo de saga
        StepVerifier.create(repository.recordSagaFailure(orderId, correlationId, errorMessage, errorType, deliveryMode))
                .verifyComplete();

        // Verificar que se registró el fallo
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM saga_failures WHERE order_id = :orderId AND error_type = :errorType")
                        .bind("orderId", orderId)
                        .bind("errorType", errorType)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNextMatches(count -> count > 0)
                .verifyComplete();
    }

    @Test
    void testInsertCompensationLog() {
        // Generar datos para la prueba
        String stepName = "payment";
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String status = "compensated";

        // Insertar registro de compensación
        StepVerifier.create(repository.insertCompensationLog(stepName, orderId, correlationId, eventId, status))
                .verifyComplete();

        // Verificar que se insertó el registro
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM compensation_log WHERE step_name = :stepName AND order_id = :orderId")
                        .bind("stepName", stepName)
                        .bind("orderId", orderId)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNextMatches(count -> count > 0)
                .verifyComplete();
    }

    @Test
    void testSaveEventHistory() {
        // Generar datos para la prueba
        String eventId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        Long orderId = System.currentTimeMillis();
        String eventType = "ORDER_CREATED";
        String operation = "CREATE";
        String outcome = "SUCCESS";
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        // Guardar historial de evento
        StepVerifier.create(repository.saveEventHistory(
                        eventId, correlationId, orderId, eventType, operation, outcome, deliveryMode))
                .verifyComplete();

        // Verificar que se guardó el historial
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM event_history WHERE event_id = :eventId AND order_id = :orderId")
                        .bind("eventId", eventId)
                        .bind("orderId", orderId)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNextMatches(count -> count > 0)
                .verifyComplete();
    }

    @Test
    void testAtMostOnceIdempotency() {
        // Este test verifica que el modo AT_MOST_ONCE maneja correctamente la idempotencia
        // Para el mismo eventId, no debería procesar el evento más de una vez

        // Generar datos para la prueba
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        // Crear un OrderEvent para la prueba
        OrderEvent orderEvent = new TestOrderEvent(orderId, correlationId, eventId, OrderEventType.ORDER_CREATED);

        // Marcar manualmente el evento como procesado
        StepVerifier.create(repository.markEventAsProcessed(eventId, deliveryMode))
                .verifyComplete();

        // Intentar guardar la orden con el mismo eventId
        // En AT_MOST_ONCE, esto debería detectar que el evento ya fue procesado y no realizar ninguna acción
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, orderEvent, deliveryMode))
                .verifyComplete();

        // Verificar que la orden NO se guardó (porque el evento ya estaba procesado)
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM orders WHERE id = :orderId")
                        .bind("orderId", orderId)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    void testCompleteOrderLifecycle() {
        // Este test simula un flujo completo de una orden usando AT_LEAST_ONCE
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String createEventId = UUID.randomUUID().toString();
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        OrderEvent createEvent = new TestOrderEvent(orderId, correlationId, createEventId, OrderEventType.ORDER_CREATED);

        // 1. Crear la orden
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, createEventId, createEvent, deliveryMode))
                .verifyComplete();

        // 2. Cambiar a "processing"
        StepVerifier.create(repository.updateOrderStatus(orderId, "processing", correlationId))
                .expectNextMatches(order -> "processing".equals(order.getStatus()))
                .verifyComplete();

        // 3. Registrar historial de evento
        String processEventId = UUID.randomUUID().toString();
        StepVerifier.create(repository.saveEventHistory(
                        processEventId, correlationId, orderId, "ORDER_PROCESSING", "UPDATE", "SUCCESS", deliveryMode))
                .verifyComplete();

        // 4. Cambiar a "payment_confirmed"
        StepVerifier.create(repository.updateOrderStatus(orderId, "payment_confirmed", correlationId))
                .expectNextMatches(order -> "payment_confirmed".equals(order.getStatus()))
                .verifyComplete();

        // 5. Cambiar a "shipped"
        StepVerifier.create(repository.updateOrderStatus(orderId, "shipped", correlationId))
                .expectNextMatches(order -> "shipped".equals(order.getStatus()))
                .verifyComplete();

        // 6. Cambiar a "delivered"
        StepVerifier.create(repository.updateOrderStatus(orderId, "delivered", correlationId))
                .expectNextMatches(order -> "delivered".equals(order.getStatus()))
                .verifyComplete();

        // Verificar todas las transiciones de estado
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM order_status_history WHERE order_id = :orderId")
                        .bind("orderId", orderId)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNext(4)  // 4 cambios de estado después del inicial
                .verifyComplete();
    }

    @Test
    void testCompensationFlow() {
        // Este test simula un flujo de compensación cuando ocurre un error
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String createEventId = UUID.randomUUID().toString();
        String paymentEventId = UUID.randomUUID().toString();
        String compensationEventId = UUID.randomUUID().toString();
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        OrderEvent createEvent = new TestOrderEvent(orderId, correlationId, createEventId, OrderEventType.ORDER_CREATED);

        // 1. Crear la orden
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, createEventId, createEvent, deliveryMode))
                .verifyComplete();

        // 2. Cambiar estado a processing
        StepVerifier.create(repository.updateOrderStatus(orderId, "processing", correlationId))
                .expectNextMatches(order -> "processing".equals(order.getStatus()))
                .verifyComplete();

        // 3. Registrar un error de pago
        StepVerifier.create(repository.recordSagaFailure(
                        orderId, correlationId, "Payment declined", "PaymentDeclinedException", deliveryMode))
                .verifyComplete();

        // 4. Registrar fallo en paso específico
        StepVerifier.create(repository.recordStepFailure(
                        "payment", orderId, correlationId, paymentEventId,
                        "Payment declined", "PaymentDeclinedException", "PAYMENT_ERROR"))
                .verifyComplete();

        // 5. Registrar compensación
        StepVerifier.create(repository.insertCompensationLog(
                        "payment_rollback", orderId, correlationId, compensationEventId, "INITIATED"))
                .verifyComplete();

        // 6. Cambiar estado a "cancelled"
        StepVerifier.create(repository.updateOrderStatus(orderId, "cancelled", correlationId))
                .expectNextMatches(order -> "cancelled".equals(order.getStatus()))
                .verifyComplete();

        // 7. Actualizar log de compensación
        StepVerifier.create(repository.insertCompensationLog(
                        "payment_rollback", orderId, correlationId, compensationEventId, "COMPLETED"))
                .verifyComplete();

        // 8. Guardar evento de historial para la compensación
        StepVerifier.create(repository.saveEventHistory(
                        compensationEventId, correlationId, orderId,
                        "COMPENSATION", "ROLLBACK", "SUCCESS", deliveryMode))
                .verifyComplete();

        // Verificar el estado final
        StepVerifier.create(repository.findOrderById(orderId))
                .expectNextMatches(order -> "cancelled".equals(order.getStatus()))
                .verifyComplete();

        // Verificar los registros de compensación
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM compensation_log WHERE order_id = :orderId")
                        .bind("orderId", orderId)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNext(2)  // 2 registros de compensación (INITIATED y COMPLETED)
                .verifyComplete();

        // Verificar registro de error en paso
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM saga_step_failures WHERE order_id = :orderId")
                        .bind("orderId", orderId)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNext(1)  // 1 registro de error de paso
                .verifyComplete();
    }

    @Test
    void testConcurrentTransactionLocks() throws Exception {
        // Este test verifica el comportamiento del sistema bajo condiciones de concurrencia
        String resourceId = "order-" + System.currentTimeMillis();
        String correlationId1 = "corr-1-" + UUID.randomUUID().toString();
        String correlationId2 = "corr-2-" + UUID.randomUUID().toString();
        int threads = 2;
        CountDownLatch latch = new CountDownLatch(threads);
        boolean[] lockResults = new boolean[threads];

        // Crear dos hilos para intentar adquirir el mismo recurso concurrentemente
        Thread t1 = new Thread(() -> {
            boolean acquired = repository.acquireTransactionLock(resourceId, correlationId1, 60).block();
            lockResults[0] = acquired;
            latch.countDown();
        });

        Thread t2 = new Thread(() -> {
            boolean acquired = repository.acquireTransactionLock(resourceId, correlationId2, 60).block();
            lockResults[1] = acquired;
            latch.countDown();
        });

        // Iniciar ambos hilos
        t1.start();
        t2.start();

        // Esperar a que ambos hilos terminen
        latch.await(5, TimeUnit.SECONDS);

        // Exactamente uno de los hilos debería haber adquirido el lock
        assert (lockResults[0] || lockResults[1]) : "Ningún hilo adquirió el lock";// Exactamente uno de los hilos debería haber adquirido el lock
        assert (lockResults[0] || lockResults[1]) : "Ningún hilo adquirió el lock";
        assert !(lockResults[0] && lockResults[1]) : "Ambos hilos adquirieron el lock, lo cual es incorrecto";

        // Liberar el lock
        if (lockResults[0]) {
            repository.releaseTransactionLock(resourceId, correlationId1).block();
        } else {
            repository.releaseTransactionLock(resourceId, correlationId2).block();
        }
    }

    @Test
    void testRecordStepFailureWithCategorization() {
        // Este test verifica el registro de fallos categorizados
        // Generar datos para la prueba
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String stepName = "payment_validation";

        // Registrar diferentes tipos de fallos
        // 1. Error de validación
        StepVerifier.create(repository.recordStepFailure(
                        stepName, orderId, correlationId, eventId + "-1",
                        "Invalid payment data", "ValidationException", "VALIDATION_ERROR"))
                .verifyComplete();

        // 2. Error de servicio externo
        StepVerifier.create(repository.recordStepFailure(
                        stepName, orderId, correlationId, eventId + "-2",
                        "Payment gateway unavailable", "ServiceException", "EXTERNAL_SERVICE_ERROR"))
                .verifyComplete();

        // 3. Error de timeout
        StepVerifier.create(repository.recordStepFailure(
                        stepName, orderId, correlationId, eventId + "-3",
                        "Payment gateway timeout", "TimeoutException", "TIMEOUT_ERROR"))
                .verifyComplete();

        // Verificar que todos los errores se registraron correctamente
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM saga_step_failures WHERE order_id = :orderId")
                        .bind("orderId", orderId)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNext(3)  // 3 errores registrados
                .verifyComplete();

        // Verificar específicamente el error de tipo TIMEOUT_ERROR
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM saga_step_failures " +
                                        "WHERE order_id = :orderId AND error_category = :category")
                        .bind("orderId", orderId)
                        .bind("category", "TIMEOUT_ERROR")
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNext(1)  // 1 error de timeout
                .verifyComplete();
    }

    @Test
    void testRetrySaveOrderDataAfterTemporaryFailure() {
        // Este test verifica el mecanismo de reintentos (simulación simplificada)
        // Dado que no podemos fácilmente simular un error de base de datos real y su recuperación,
        // creamos un escenario donde marcamos un evento como procesado y luego intentamos
        // guardar una orden con ese evento - lo que en AT_MOST_ONCE sería un reintento legítimo

        // Generar datos para la prueba
        Long orderId = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        // Marcar el evento como procesado (simula un intento previo que falló parcialmente)
        StepVerifier.create(repository.markEventAsProcessed(eventId, deliveryMode))
                .verifyComplete();

        // Intentar guardar la orden de nuevo
        OrderEvent orderEvent = new TestOrderEvent(orderId, correlationId, eventId, OrderEventType.ORDER_CREATED);
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, orderEvent, deliveryMode))
                .verifyComplete();

        // Verificar que la orden NO se guardó (porque el evento ya estaba procesado)
        StepVerifier.create(databaseClient.sql(
                                "SELECT COUNT(*) FROM orders WHERE id = :orderId")
                        .bind("orderId", orderId)
                        .map((row, metadata) -> row.get(0, Integer.class))
                        .one())
                .expectNext(0)
                .verifyComplete();

        // Verificar que el evento sigue marcado como procesado
        StepVerifier.create(repository.isEventProcessed(eventId, deliveryMode))
                .expectNext(true)
                .verifyComplete();
    }

    /**
     * Implementación simple de OrderEvent para pruebas
     */
    private static class TestOrderEvent implements OrderEvent {
        private final Long orderId;
        private final String correlationId;
        private final String eventId;
        private final OrderEventType type;

        public TestOrderEvent(Long orderId, String correlationId, String eventId, OrderEventType type) {
            this.orderId = orderId;
            this.correlationId = correlationId;
            this.eventId = eventId;
            this.type = type;
        }

        @Override
        public String getEventId() {
            return eventId;
        }

        @Override
        public String getCorrelationId() {
            return correlationId;
        }

        @Override
        public Long getOrderId() {
            return orderId;
        }

        @Override
        public OrderEventType getType() {
            return type;
        }

        @Override
        public String toJson() {
            return "{\"type\":\"" + type.name() +
                    "\",\"orderId\":" + orderId +
                    ",\"correlationId\":\"" + correlationId +
                    "\",\"eventId\":\"" + eventId + "\"}";
        }

        @Override
        public String getExternalReference() {
            // Para pruebas, no necesitamos una referencia externa real
            return null;
        }
    }
}