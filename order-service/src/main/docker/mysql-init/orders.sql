CREATE TABLE IF NOT EXISTS orders (
    id BIGINT PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    INDEX idx_correlation_id (correlation_id)
);

CREATE TABLE IF NOT EXISTS outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_correlation_id (correlation_id)
);

CREATE TABLE IF NOT EXISTS processed_events (
    event_id VARCHAR(36) PRIMARY KEY,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

DELIMITER //
DROP PROCEDURE IF EXISTS insert_outbox //
CREATE PROCEDURE insert_outbox(
    IN p_event_type VARCHAR(50),
    IN p_correlation_id VARCHAR(36),
    IN p_event_id VARCHAR(36),
    IN p_payload TEXT
)
BEGIN
    INSERT INTO outbox (event_type, correlation_id, event_id, payload)
    VALUES (p_event_type, p_correlation_id, p_event_id, p_payload);
END //
DELIMITER ;