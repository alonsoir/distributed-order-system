-- Tabla principal de órdenes con estados detallados
CREATE TABLE orders (
    id BIGINT PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version INT DEFAULT 0,
    INDEX idx_status (status),
    INDEX idx_correlation (correlation_id)
);

-- Patrón outbox para consistencia transaccional
CREATE TABLE outbox (
    id SERIAL PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    event_id VARCHAR(100) NOT NULL UNIQUE,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    published_at TIMESTAMP NULL,
    INDEX idx_status (status),
    INDEX idx_event_id (event_id),
    INDEX idx_correlation (correlation_id)
);

-- Control de idempotencia
CREATE TABLE processed_events (
    event_id VARCHAR(100) PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL,
    INDEX idx_processed_at (processed_at)
);

-- Historial completo para auditoría
CREATE TABLE event_history (
    id SERIAL PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    outcome VARCHAR(500) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    INDEX idx_correlation (correlation_id),
    INDEX idx_order (order_id),
    INDEX idx_timestamp (timestamp)
);

-- Registro de compensaciones
CREATE TABLE compensation_log (
    id SERIAL PRIMARY KEY,
    step_name VARCHAR(100) NOT NULL,
    order_id BIGINT NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    event_id VARCHAR(100) NOT NULL,
    status VARCHAR(100) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    INDEX idx_order (order_id),
    INDEX idx_correlation (correlation_id)
);

-- Historial de cambios de estado
CREATE TABLE order_status_history (
    id SERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    INDEX idx_order (order_id),
    INDEX idx_timestamp (timestamp)
);

-- Registro de fallos para análisis
CREATE TABLE saga_step_failures (
    id SERIAL PRIMARY KEY,
    step_name VARCHAR(100) NOT NULL,
    order_id BIGINT NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    event_id VARCHAR(100) NOT NULL,
    error_message TEXT NOT NULL,
    error_type VARCHAR(255) NOT NULL,
    error_category VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    handled BOOLEAN DEFAULT true,
    INDEX idx_order (order_id),
    INDEX idx_correlation (correlation_id),
    INDEX idx_step (step_name),
    INDEX idx_timestamp (timestamp)
);

-- Registro de fallos de saga completa
CREATE TABLE saga_failures (
    id SERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    error_message TEXT NOT NULL,
    error_type VARCHAR(255) NOT NULL,
    error_category VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    requires_attention BOOLEAN DEFAULT false,
    resolved BOOLEAN DEFAULT false,
    INDEX idx_order (order_id),
    INDEX idx_attention (requires_attention),
    INDEX idx_timestamp (timestamp)
);

-- Eventos fallidos standalone
CREATE TABLE failed_events (
    id SERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    event_id VARCHAR(100) NOT NULL UNIQUE,
    reason TEXT NOT NULL,
    external_reference VARCHAR(255) NULL,
    timestamp TIMESTAMP NOT NULL,
    handled BOOLEAN DEFAULT false,
    INDEX idx_handled (handled),
    INDEX idx_timestamp (timestamp)
);