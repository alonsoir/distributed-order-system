-- order-service/src/test/resources/schema-tables.sql
-- Esquema simplificado para tests de integración
-- Solo tablas - los procedimientos se crean en @BeforeAll

-- Desactivar verificación de claves foráneas durante la inicialización
SET FOREIGN_KEY_CHECKS = 0;

-- Tabla principal de órdenes con campo para indicar el modo
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    external_reference VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version INT DEFAULT 0,
    delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE',
    INDEX idx_correlation_id (correlation_id),
    INDEX idx_status (status)
);

-- Patrón outbox (primario para AT LEAST ONCE, pero también útil para AT MOST ONCE)
CREATE TABLE IF NOT EXISTS outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    payload TEXT NOT NULL,
    topic VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'PENDING',
    published_at TIMESTAMP NULL,
    delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE',
    UNIQUE KEY unique_event_id (event_id),
    INDEX idx_correlation_id (correlation_id),
    INDEX idx_status (status),
    INDEX idx_event_id (event_id),
    INDEX idx_delivery_mode (delivery_mode)
);

-- Eventos procesados (esencial para ambos modos)
CREATE TABLE IF NOT EXISTS processed_events (
    event_id VARCHAR(36) PRIMARY KEY,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE',
    INDEX idx_processed_at (processed_at),
    INDEX idx_delivery_mode (delivery_mode)
);

-- Historial de eventos (para ambos modos)
CREATE TABLE IF NOT EXISTS event_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    operation VARCHAR(50) NOT NULL,
    outcome VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE',
    INDEX idx_event_id (event_id),
    INDEX idx_correlation_id (correlation_id),
    INDEX idx_order_id (order_id),
    INDEX idx_timestamp (timestamp)
);

-- Historial de estados de órdenes (para ambos modos)
CREATE TABLE IF NOT EXISTS order_status_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order_id (order_id),
    INDEX idx_timestamp (timestamp)
);

-- Registro de compensaciones (principalmente para AT LEAST ONCE)
CREATE TABLE IF NOT EXISTS compensation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    step_name VARCHAR(50) NOT NULL,
    order_id BIGINT NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order_id (order_id),
    INDEX idx_correlation_id (correlation_id),
    INDEX idx_status (status)
);

-- Registro de fallos de pasos específicos
CREATE TABLE IF NOT EXISTS saga_step_failures (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    step_name VARCHAR(50) NOT NULL,
    order_id BIGINT NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    error_message TEXT NOT NULL,
    error_type VARCHAR(100) NOT NULL,
    error_category VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    handled BOOLEAN DEFAULT false,
    INDEX idx_order_id (order_id),
    INDEX idx_correlation_id (correlation_id),
    INDEX idx_step_name (step_name),
    INDEX idx_handled (handled)
);

-- Registro de fallos generales
CREATE TABLE IF NOT EXISTS saga_failures (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    error_message TEXT NOT NULL,
    error_type VARCHAR(100) NOT NULL,
    error_category VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    requires_attention BOOLEAN DEFAULT false,
    resolved BOOLEAN DEFAULT false,
    delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE',
    INDEX idx_order_id (order_id),
    INDEX idx_requires_attention (requires_attention),
    INDEX idx_delivery_mode (delivery_mode)
);

-- Eventos fallidos
CREATE TABLE IF NOT EXISTS failed_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    reason TEXT NOT NULL,
    external_reference VARCHAR(36),
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    handled BOOLEAN DEFAULT false,
    delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE',
    UNIQUE KEY unique_event_id (event_id),
    INDEX idx_order_id (order_id),
    INDEX idx_handled (handled),
    INDEX idx_delivery_mode (delivery_mode)
);

-- Sistema de bloqueo para AT MOST ONCE
CREATE TABLE IF NOT EXISTS transaction_locks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    lock_uuid VARCHAR(36) NOT NULL,
    locked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    released BOOLEAN DEFAULT false,
    UNIQUE KEY unique_resource_and_lock (resource_id, lock_uuid),
    INDEX idx_correlation_id (correlation_id),
    INDEX idx_expires_at (expires_at),
    INDEX idx_lock_uuid (lock_uuid),
    INDEX idx_resource_id (resource_id)
);

-- Restaurar verificación de claves foráneas
SET FOREIGN_KEY_CHECKS = 1;