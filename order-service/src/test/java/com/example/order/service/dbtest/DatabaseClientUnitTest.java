package com.example.order.service.dbtest;

import com.example.order.domain.Order;
import io.asyncer.r2dbc.mysql.MySqlConnectionFactoryProvider;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;

class DatabaseClientPureTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("orders")
            .withUsername("root")
            .withPassword("root")
            .withInitScript("schema.sql");

    private static DatabaseClient client;

    @BeforeAll
    static void setUp() {
        // montar la ConnectionFactory directamente
        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(DRIVER, "mysql")
                .option(HOST,   mysql.getHost())
                .option(PORT,   mysql.getFirstMappedPort())
                .option(USER,   mysql.getUsername())
                .option(PASSWORD, mysql.getPassword())
                .option(DATABASE, mysql.getDatabaseName())
                .build();
        ConnectionFactory cf = new MySqlConnectionFactoryProvider().create(options);
        client = DatabaseClient.create(cf);
    }

    @Test
    void pureInsertAndFetch() {
        Long orderId = 1L;
        String corr = "corr-123";
        String status = "pending";

        Mono<Void> insert = client
                .sql("INSERT INTO orders (id,status,correlation_id) VALUES (:id,:st,:corr)")
                .bind("id", orderId)
                .bind("st", status)
                .bind("corr", corr)
                .then();

        Mono<Order> fetch = client
                .sql("SELECT id,status,correlation_id FROM orders WHERE id = :id")
                .bind("id", orderId)
                .map(r -> new Order(
                        r.get("id", Long.class),
                        r.get("status", String.class),
                        r.get("correlation_id", String.class)))
                .one();

        StepVerifier.create(insert.then(fetch))
                .expectNextMatches(o ->
                        o.id().equals(orderId)
                                && o.status().equals(status)
                                && o.correlationId().equals(corr)
                )
                .verifyComplete();
    }
}
