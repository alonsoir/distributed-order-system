CREATE TABLE orders (
    id BIGINT PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(255) NOT NULL
);

CREATE TABLE outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(255) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE processed_events (
    event_id VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

DELIMITER //

CREATE PROCEDURE insert_outbox(
    IN p_event_type VARCHAR(100),
    IN p_correlation_id VARCHAR(255),
    IN p_event_id VARCHAR(255),
    IN p_payload TEXT
)
BEGIN
    INSERT INTO outbox (event_type, correlation_id, event_id, payload)
    VALUES (p_event_type, p_correlation_id, p_event_id, p_payload);
END //

DELIMITER ;