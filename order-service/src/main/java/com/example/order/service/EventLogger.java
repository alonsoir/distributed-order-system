package com.example.order.service;

import com.example.order.events.OrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EventLogger {
    private static final Logger log = LoggerFactory.getLogger(EventLogger.class);
    private static final Path LOG_DIR = Paths.get("failed-events");
    private final ObjectMapper objectMapper;

    public Mono<Void> logEvent(OrderEvent event, String topic, Throwable error) {
        return Mono.fromCallable(() -> {
            try {
                Files.createDirectories(LOG_DIR);
                Path logFile = LOG_DIR.resolve(event.getEventId() + "_" + Instant.now().toEpochMilli() + ".json");
                try (FileWriter writer = new FileWriter(logFile.toFile())) {
                    Map<String, Object> logEntry = new HashMap<>();
                    logEntry.put("eventId", event.getEventId());
                    logEntry.put("correlationId", event.getCorrelationId());
                    logEntry.put("orderId", event.getOrderId());
                    logEntry.put("type", event.getType().name());
                    logEntry.put("topic", topic);
                    logEntry.put("payload", event.toJson());
                    logEntry.put("error", error.getMessage());
                    logEntry.put("timestamp", Instant.now().toEpochMilli());
                    objectMapper.writeValue(writer, logEntry);
                }
                log.info("Logged failed event {} to disk for topic {}", event.getType().name(), topic);
                return null;
            } catch (IOException e) {
                log.error("Failed to log event {} to disk: {}", event.getEventId(), e.getMessage());
                throw new RuntimeException(e);
            }
        }).then();
    }
}