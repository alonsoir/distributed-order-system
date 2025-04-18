package com.example.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.junit.jupiter.api.Test;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

public class SimpleTestcontainersTest {
    private static final Logger logger = LoggerFactory.getLogger(SimpleTestcontainersTest.class);

    @Test
    public void shouldStartMySQLContainer() throws SQLException {
        try (MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36-debian"))
                .withDatabaseName("test")
                .withUsername("test")
                .withPassword("test")
                .waitingFor(Wait.forLogMessage(".*ready for connections.*", 1))) {
            logger.info("Starting MySQL container with image: mysql:8.0.36-debian");
            mysql.start();
            logger.info("MySQL container started. JDBC URL: {}", mysql.getJdbcUrl());
            assertTrue(mysql.isRunning(), "MySQL container should be running");

            // Test database connectivity
            String jdbcUrl = mysql.getJdbcUrl();
            String username = mysql.getUsername();
            String password = mysql.getPassword();
            try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
                logger.info("Database connection established");
                assertNotNull(conn, "Database connection should not be null");
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT 1");
                assertTrue(rs.next(), "ResultSet should have a row");
                assertEquals(1, rs.getInt(1), "Query should return 1");
            } catch (SQLException e) {
                logger.error("Failed to connect to database: {}", e.getMessage(), e);
                throw e;
            }
        }
    }
}