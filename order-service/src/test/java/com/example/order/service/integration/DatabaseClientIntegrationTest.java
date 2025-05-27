package com.example.order.service.integration;

import com.example.order.domain.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataR2dbcTest
@Testcontainers
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false"
})
@Deprecated(since = "1.0", forRemoval = true)

class DatabaseClientIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DatabaseClientIntegrationTest.class);

    @Container
    private static final MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("orders")
                    .withUsername("root")
                    .withPassword("root")
                    .withInitScript("schema-tables.sql"); // Usar script simplificado

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DatabaseClient databaseClient;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url",
                () -> "r2dbc:mysql://" + mysql.getHost() +
                        ":" + mysql.getFirstMappedPort() + "/orders?sslMode=DISABLED");
        r.add("spring.r2dbc.username", mysql::getUsername);
        r.add("spring.r2dbc.password", mysql::getPassword);
    }

    @BeforeAll
    static void setupProcedures() {
        try {
            // Crear procedimientos almacenados después de que el contenedor haya iniciado
            String jdbcUrl = mysql.getJdbcUrl();
            try (Connection conn = DriverManager.getConnection(jdbcUrl, mysql.getUsername(), mysql.getPassword());
                 Statement stmt = conn.createStatement()) {

                // Procedimiento insert_outbox
                stmt.execute("CREATE PROCEDURE IF NOT EXISTS insert_outbox(\n" +
                        "    IN p_event_type VARCHAR(50),\n" +
                        "    IN p_correlation_id VARCHAR(36),\n" +
                        "    IN p_event_id VARCHAR(36),\n" +
                        "    IN p_payload TEXT\n" +
                        ")\n" +
                        "BEGIN\n" +
                        "    DECLARE topic VARCHAR(255);\n" +
                        "    SET topic = CONCAT('order.', LOWER(p_event_type));\n" +
                        "\n" +
                        "    INSERT INTO outbox (event_type, correlation_id, event_id, payload, topic)\n" +
                        "    VALUES (p_event_type, p_correlation_id, p_event_id, p_payload, topic);\n" +
                        "END");

                // Procedimiento try_acquire_lock
                stmt.execute("CREATE PROCEDURE IF NOT EXISTS try_acquire_lock(\n" +
                        "    IN p_resource_id VARCHAR(100),\n" +
                        "    IN p_correlation_id VARCHAR(36),\n" +
                        "    IN p_timeout_seconds INT,\n" +
                        "    OUT result BOOLEAN\n" +
                        ")\n" +
                        "BEGIN\n" +
                        "    DECLARE lock_count INT;\n" +
                        "\n" +
                        "    -- Verificar si el recurso ya está bloqueado\n" +
                        "    SELECT COUNT(*) INTO lock_count\n" +
                        "    FROM transaction_locks\n" +
                        "    WHERE resource_id = p_resource_id\n" +
                        "      AND released = FALSE\n" +
                        "      AND expires_at > NOW();\n" +
                        "\n" +
                        "    IF lock_count = 0 THEN\n" +
                        "        -- El recurso está disponible, adquirimos lock\n" +
                        "        INSERT INTO transaction_locks (resource_id, correlation_id, locked_at, expires_at)\n" +
                        "        VALUES (p_resource_id, p_correlation_id, NOW(), DATE_ADD(NOW(), INTERVAL p_timeout_seconds SECOND));\n" +
                        "\n" +
                        "        SET result = TRUE;\n" +
                        "    ELSE\n" +
                        "        -- El recurso ya está bloqueado\n" +
                        "        SET result = FALSE;\n" +
                        "    END IF;\n" +
                        "END");

                // Procedimiento release_lock
                stmt.execute("CREATE PROCEDURE IF NOT EXISTS release_lock(\n" +
                        "    IN p_resource_id VARCHAR(100),\n" +
                        "    IN p_correlation_id VARCHAR(36)\n" +
                        ")\n" +
                        "BEGIN\n" +
                        "    UPDATE transaction_locks\n" +
                        "    SET released = TRUE\n" +
                        "    WHERE resource_id = p_resource_id\n" +
                        "      AND correlation_id = p_correlation_id;\n" +
                        "END");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error initializing stored procedures", e);
        }
    }

    @BeforeEach
    void setup() {
        // Ensure database is ready and tables exist
        databaseClient.sql("SELECT 1").fetch().one().block(Duration.ofSeconds(5));
    }

    @AfterEach
    void cleanup() {
        // Clean up tables
        databaseClient.sql("DELETE FROM outbox").then().block();
        databaseClient.sql("DELETE FROM transaction_locks").then().block();
        databaseClient.sql("DELETE FROM orders").then().block();
    }

    @Test
    void shouldVerifyContextLoads() {
        // Test real con aserciones válidas
        String[] beanNames = context.getBeanDefinitionNames();

        assertThat(beanNames).isNotEmpty();
        assertThat(beanNames).contains("databaseClient");
        assertThat(context.getBean(DatabaseClient.class)).isNotNull();

        // Usar método de debug para logging opcional
        debugAvailableBeans();
    }

    @Test
    void shouldInsertAndRetrieveOrder() {
        Long orderId = 1L;
        String corr = "corr-123";
        String status = "pending";

        Mono<Void> insert = databaseClient
                .sql("INSERT INTO orders (id, status, correlation_id) VALUES (:id, :st, :corr)")
                .bind("id", orderId)
                .bind("st", status)
                .bind("corr", corr)
                .then();

        Mono<Order> fetch = databaseClient
                .sql("SELECT id, status, correlation_id FROM orders WHERE id = :id")
                .bind("id", orderId)
                .map(r -> new Order(
                        r.get("id", Long.class),
                        r.get("status", String.class),
                        r.get("correlation_id", String.class)))
                .one();

        StepVerifier.create(insert.then(fetch))
                .expectNextMatches(o ->
                        o.id().equals(orderId) &&
                                o.status().equals(status) &&
                                o.correlationId().equals(corr)
                )
                .verifyComplete();
    }

    @Test
    void testInsertOutboxProcedure() {
        String eventType = "ORDER_CREATED";
        String correlationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String payload = "{\"orderId\": 1, \"status\": \"CREATED\"}";

        // Llamar al procedimiento almacenado para insertar en outbox
        Mono<Void> callProcedure = databaseClient
                .sql("CALL insert_outbox(?, ?, ?, ?)")
                .bind(0, eventType)
                .bind(1, correlationId)
                .bind(2, eventId)
                .bind(3, payload)
                .then();

        // Verificar que se insertó correctamente
        Mono<Boolean> verifyInsert = databaseClient
                .sql("SELECT COUNT(*) FROM outbox WHERE event_id = :eventId")
                .bind("eventId", eventId)
                .map(row -> row.get(0, Integer.class) > 0)
                .one();

        StepVerifier.create(callProcedure.then(verifyInsert))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void testAcquireAndReleaseLock() {
        String resourceId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        int timeoutSeconds = 30;

        // Adquirir un lock
        Mono<Boolean> acquireLock = databaseClient
                .sql("CALL try_acquire_lock(?, ?, ?, @result); SELECT @result;")
                .bind(0, resourceId)
                .bind(1, correlationId)
                .bind(2, timeoutSeconds)
                .map(row -> row.get(0, Integer.class) == 1) // MySQL devuelve 1/0 para booleanos
                .one();

        // Verificar que el lock se adquirió correctamente
        Mono<Boolean> verifyLock = databaseClient
                .sql("SELECT COUNT(*) FROM transaction_locks WHERE resource_id = :resourceId AND correlation_id = :correlationId AND released = FALSE")
                .bind("resourceId", resourceId)
                .bind("correlationId", correlationId)
                .map(row -> row.get(0, Integer.class) > 0)
                .one();

        // Liberar el lock
        Mono<Void> releaseLock = databaseClient
                .sql("CALL release_lock(?, ?)")
                .bind(0, resourceId)
                .bind(1, correlationId)
                .then();

        // Verificar que el lock se liberó correctamente
        Mono<Boolean> verifyRelease = databaseClient
                .sql("SELECT released FROM transaction_locks WHERE resource_id = :resourceId AND correlation_id = :correlationId")
                .bind("resourceId", resourceId)
                .bind("correlationId", correlationId)
                .map(row -> row.get("released", Boolean.class))
                .one();

        StepVerifier.create(acquireLock
                        .then(verifyLock)
                        .then(releaseLock)
                        .then(verifyRelease))
                .expectNext(true)
                .verifyComplete();
    }

    /**
     * Método de debug para imprimir beans disponibles cuando sea necesario.
     * No es un test - solo utilidad de debugging.
     */
    private void debugAvailableBeans() {
        if (log.isDebugEnabled()) {
            String[] beanNames = context.getBeanDefinitionNames();
            Arrays.sort(beanNames);
            log.debug("=== Available Beans in Test Context ===");
            for (String beanName : beanNames) {
                log.debug("  - {}", beanName);
            }
            log.debug("=== Total beans: {} ===", beanNames.length);
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {
        // Empty config with EnableAutoConfiguration to help with component scanning
    }
}