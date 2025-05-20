-- order-service/src/main/docker/mysql-init/orders.sql
-- Esquema unificado para contenedor Docker (MySQL/MariaDB)
-- Soporta ambas estrategias: AT LEAST ONCE y AT MOST ONCE

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
    UNIQUE KEY unique_resource_and_lock (resource_id, lock_uuid),  -- Cambio aquí: ahora la combinación es única
    INDEX idx_correlation_id (correlation_id),
    INDEX idx_expires_at (expires_at),
    INDEX idx_lock_uuid (lock_uuid),
    INDEX idx_resource_id (resource_id)  -- Añadido índice separado para resource_id ya que ya no es único
);

-- Procedimientos almacenados para ambos modos
DELIMITER //

-- Para AT LEAST ONCE: Inserción en outbox
DROP PROCEDURE IF EXISTS insert_outbox //
CREATE PROCEDURE insert_outbox(
    IN p_event_type VARCHAR(50),
    IN p_correlation_id VARCHAR(36),
    IN p_event_id VARCHAR(36),
    IN p_payload TEXT
)
BEGIN
    -- Compatibilidad con el procedimiento existente
    DECLARE v_topic VARCHAR(255);
    SET v_topic = CONCAT('order.', LOWER(p_event_type));

    INSERT INTO outbox (event_type, correlation_id, event_id, payload, topic, delivery_mode)
    VALUES (p_event_type, p_correlation_id, p_event_id, p_payload, v_topic, 'AT_LEAST_ONCE');
END //

-- Para AT MOST ONCE: Gestión de locks
DROP PROCEDURE IF EXISTS try_acquire_lock //
CREATE PROCEDURE try_acquire_lock(
    IN p_resource_id VARCHAR(100),
    IN p_correlation_id VARCHAR(36),
    IN p_lock_timeout_seconds INT,
    IN p_lock_uuid VARCHAR(36),
    OUT p_acquired BOOLEAN
)
BEGIN
    DECLARE lock_count INT;

    START TRANSACTION;

    -- Verificar si el recurso ya está bloqueado
    -- Nota: ahora verificamos si el recurso específico está bloqueado,
    -- pero permitimos bloqueos múltiples con diferentes UUIDs
    SELECT COUNT(*) INTO lock_count
    FROM transaction_locks
    WHERE resource_id = p_resource_id AND
          (released = false AND expires_at > NOW()) AND
          lock_uuid = p_lock_uuid  -- Verificar solo para este UUID específico
    FOR UPDATE;

    IF lock_count = 0 THEN
        -- El recurso está disponible o ya tiene otros bloqueos con diferentes UUIDs
        INSERT INTO transaction_locks (resource_id, correlation_id, lock_uuid, locked_at, expires_at)
        VALUES (p_resource_id, p_correlation_id, p_lock_uuid, NOW(), DATE_ADD(NOW(), INTERVAL p_lock_timeout_seconds SECOND));

        SET p_acquired = true;
    ELSE
        -- El recurso ya está bloqueado con este UUID
        SET p_acquired = false;
    END IF;

    COMMIT;
END //

-- Para AT MOST ONCE: Liberación de lock
DROP PROCEDURE IF EXISTS release_lock //
CREATE PROCEDURE release_lock(
    IN p_resource_id VARCHAR(100),
    IN p_correlation_id VARCHAR(36),
    IN p_lock_uuid VARCHAR(36)
)
BEGIN
    UPDATE transaction_locks
    SET released = true
    WHERE resource_id = p_resource_id
    AND correlation_id = p_correlation_id
    AND lock_uuid = p_lock_uuid;
END //

-- Actualización del procedimiento insert_outbox para compatibilidad con la versión anterior
DROP PROCEDURE IF EXISTS insert_outbox_legacy //
CREATE PROCEDURE insert_outbox_legacy(
    IN p_event_type VARCHAR(50),
    IN p_correlation_id VARCHAR(36),
    IN p_event_id VARCHAR(36),
    IN p_payload TEXT,
    IN p_topic VARCHAR(255)
)
BEGIN
    INSERT INTO outbox (event_type, correlation_id, event_id, payload, topic, created_at)
    VALUES (p_event_type, p_correlation_id, p_event_id, p_payload, p_topic, NOW());
END //

DELIMITER ;

-- Restaurar verificación de claves foráneas
SET FOREIGN_KEY_CHECKS = 1;