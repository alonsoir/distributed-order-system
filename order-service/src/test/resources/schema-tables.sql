-- Esquema unificado para soportar AT LEAST ONCE y AT MOST ONCE (versión MySQL)

-- Tabla principal de órdenes con campo para indicar el modo
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    external_reference VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INT DEFAULT 0,
    delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE'
);
CREATE INDEX idx_correlation_id ON orders(correlation_id);
CREATE INDEX idx_status ON orders(status);

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
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id)
);
CREATE INDEX idx_outbox_correlation_id ON outbox(correlation_id);
CREATE INDEX idx_outbox_status ON outbox(status);
CREATE INDEX idx_outbox_event_id ON outbox(event_id);
CREATE INDEX idx_outbox_delivery_mode ON outbox(delivery_mode);

-- Eventos procesados (esencial para ambos modos)
CREATE TABLE IF NOT EXISTS processed_events (
    event_id VARCHAR(36) PRIMARY KEY,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE'
);

-- Historial de eventos y estados (para ambos modos)
CREATE TABLE IF NOT EXISTS event_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    operation VARCHAR(50) NOT NULL,
    outcome VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE'
);
CREATE INDEX idx_event_history_correlation_id ON event_history(correlation_id);
CREATE INDEX idx_event_history_order_id ON event_history(order_id);

-- Historial de estados de órdenes (para ambos modos)
CREATE TABLE IF NOT EXISTS order_status_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_order_status_history_order_id ON order_status_history(order_id);

-- Registro de compensaciones (principalmente para AT LEAST ONCE)
CREATE TABLE IF NOT EXISTS compensation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    step_name VARCHAR(50) NOT NULL,
    order_id BIGINT NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_compensation_log_order_id ON compensation_log(order_id);
CREATE INDEX idx_compensation_log_correlation_id ON compensation_log(correlation_id);

-- Registro de fallos (para ambos modos)
CREATE TABLE IF NOT EXISTS saga_failures (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    error_message TEXT NOT NULL,
    error_type VARCHAR(100) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    requires_attention BOOLEAN DEFAULT FALSE,
    delivery_mode VARCHAR(20) DEFAULT 'AT_LEAST_ONCE'
);
CREATE INDEX idx_saga_failures_order_id ON saga_failures(order_id);

-- Sistema de bloqueo para AT MOST ONCE
CREATE TABLE IF NOT EXISTS transaction_locks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    locked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,
    released BOOLEAN DEFAULT FALSE,
    CONSTRAINT unique_resource UNIQUE (resource_id)
);
CREATE INDEX idx_transaction_locks_correlation_id ON transaction_locks(correlation_id);
CREATE INDEX idx_transaction_locks_expires_at ON transaction_locks(expires_at);