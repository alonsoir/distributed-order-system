package com.example.order.service;

import com.example.order.events.DefaultOrderEvent;
import com.example.order.events.OrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Clase base abstracta que proporciona funcionalidad común para gestores de DLQ.
 */
public abstract class AbstractDLQManager {
    private static final Logger log = LoggerFactory.getLogger(AbstractDLQManager.class);

    // Constantes comunes
    protected static final String DLQ_SUCCESS_COUNTER = "dead_letter_queue_success";
    protected static final String DLQ_FAILURE_COUNTER = "dead_letter_queue_failure";
    protected static final String DLQ_KEY = "failed-outbox-events";

    // Componentes necesarios en clases derivadas - definidos como protected
    protected final ReactiveRedisTemplate<String, Object> redisTemplate;
    protected final ObjectMapper objectMapper;
    protected final MeterRegistry meterRegistry;
    protected final RedisStatusChecker redisStatusChecker;
    protected final EventLogger eventLogger;
    protected final DLQConfig dlqConfig;
    protected final Path logDir;

    // Colecciones para rastrear eventos procesados
    protected final Set<String> processedEvents = ConcurrentHashMap.newKeySet();
    protected final Map<String, Long> eventTimestamps = new ConcurrentHashMap<>();

    /**
     * Constructor base para la clase abstracta.
     */
    protected AbstractDLQManager(
            ReactiveRedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            RedisStatusChecker redisStatusChecker,
            EventLogger eventLogger,
            DLQConfig dlqConfig,
            Path logDir) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.redisStatusChecker = redisStatusChecker;
        this.eventLogger = eventLogger;
        this.dlqConfig = dlqConfig;
        this.logDir = logDir;
    }

    /**
     * Configuration properties for DLQ processing.
     */
    @ConfigurationProperties(prefix = "dlq")
    @Validated
    @Getter
    @Setter
    public static class DLQConfig {
        @Min(1)
        private long retryMaxAttempts = 3;

        @Min(1)
        private long backoffSeconds = 1;

        @Min(1000)
        private long processInterval = 60000;

        @Min(1)
        private long eventExpirationHours = 24;
    }

    /**
     * Reconstruye un OrderEvent a partir de los datos almacenados.
     *
     * @param data los datos del evento
     * @return el OrderEvent reconstruido
     * @throws RuntimeException si la reconstrucción falla
     */
    public OrderEvent reconstructEvent(Map<String, Object> data) {
        try {
            String json = (String) data.get("payload");
            if (json == null) {
                throw new IllegalArgumentException("Payload is missing in event data");
            }
            return DefaultOrderEvent.fromJson(json);
        } catch (Exception e) {
            log.error("Failed to reconstruct event from data: {}", data, e);
            throw new RuntimeException("Event reconstruction failed", e);
        }
    }

    /**
     * Crear una entrada para la DLQ con toda la información necesaria sobre el evento fallido.
     */
    protected Map<String, Object> createDLQEntry(OrderEvent event, Throwable error, String topic) {
        Map<String, Object> failedEvent = new HashMap<>();
        failedEvent.put("eventId", event.getEventId());
        failedEvent.put("correlationId", event.getCorrelationId());
        failedEvent.put("orderId", event.getOrderId());
        failedEvent.put("type", event.getType() != null ? event.getType().name() : null);
        failedEvent.put("topic", topic);
        failedEvent.put("payload", event.toJson());
        failedEvent.put("error", error != null ? error.getMessage() : "Unknown error");
        failedEvent.put("timestamp", System.currentTimeMillis());
        failedEvent.put("retries", 0);
        return failedEvent;
    }

    /**
     * Creates metric tags for monitoring.
     */
    protected Map<String, String> createMetricTags(OrderEvent event, String stepName, String topic) {
        return Map.of(
                "eventType", event.getType() != null ? event.getType().name() : "unknown",
                "step", stepName != null ? stepName : "unknown",
                "topic", topic != null ? topic : "unknown"
        );
    }

    /**
     * Handles successful push to DLQ.
     */
    protected void handlePushSuccess(OrderEvent event, String topic, Map<String, String> tags) {
        incrementCounter(DLQ_SUCCESS_COUNTER, tags);
        log.info("Pushed event {} to DLQ for topic {} and order {}",
                event.getType() != null ? event.getType().name() : "unknown",
                topic, event.getOrderId());
        addProcessedEvent(event.getEventId());
    }

    /**
     * Handles error during push to DLQ.
     */
    protected void handlePushError(Map<String, String> tags) {
        incrementCounter(DLQ_FAILURE_COUNTER, tags);
    }

    /**
     * Handles error with fallback to file logging.
     */
    protected Mono<EventPublishOutcome<OrderEvent>> handlePushErrorWithFallback(
            OrderEvent event, String topic, Throwable originalError, Throwable pushError) {
        log.error("Failed to push event to DLQ: {}", pushError.getMessage());
        // If Redis push fails, try file logging as fallback
        return eventLogger.logEvent(event, topic, originalError)
                .thenReturn(EventPublishOutcome.dlqFailure(event, pushError));
    }

    /**
     * Creates a retry specification for Redis operations.
     */
    protected Retry createRetrySpec() {
        return Retry.backoff(dlqConfig.getRetryMaxAttempts(), Duration.ofSeconds(dlqConfig.getBackoffSeconds()))
                .filter(t -> t instanceof RedisException);
    }

    /**
     * Processes the next item in the DLQ.
     */
    protected Mono<Void> processNextDLQItem() {
        return redisTemplate.opsForList()
                .rightPop(DLQ_KEY)
                .flatMap(obj -> {
                    if (!(obj instanceof Map)) {
                        log.error("Invalid DLQ entry, expected Map, got {}", obj.getClass());
                        return Mono.empty();
                    }
                    return processRetryEvent((Map<String, Object>) obj);
                })
                .onErrorResume(e -> {
                    log.error("Error processing DLQ: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Retries a failed event from the DLQ or moves it to log-based recovery if max retries are reached.
     */
    protected Mono<Void> processRetryEvent(Map<String, Object> failedEvent) {
        String eventId = (String) failedEvent.get("eventId");
        if (eventId == null) {
            log.error("Found DLQ entry without eventId: {}", failedEvent);
            return Mono.empty();
        }

        if (processedEvents.contains(eventId)) {
            log.info("Skipping duplicate event {} in DLQ", eventId);
            return Mono.empty();
        }

        try {
            OrderEvent event = reconstructEvent(failedEvent);
            String topic = (String) failedEvent.get("topic");
            int retries = ((Number) failedEvent.getOrDefault("retries", 0)).intValue();

            if (retries >= dlqConfig.getRetryMaxAttempts()) {
                log.warn("Max retries reached for event {}, moving to log-based recovery", eventId);
                return eventLogger.logEvent(event, topic, new RuntimeException("Max retries reached"));
            }

            // Increment retries
            failedEvent.put("retries", retries + 1);

            // Here we would ideally use an EventPublisher but to avoid circular dependency
            // we use EventLogger for demonstration purposes
            return eventLogger.logEvent(event, topic, new RuntimeException("Retry attempt " + (retries + 1)));
        } catch (Exception e) {
            log.error("Error processing DLQ entry {}: {}", eventId, e.getMessage());
            return Mono.empty();
        }
    }

    /**
     * Processes events from log-based DLQ when Redis is unavailable.
     */
    protected void processLogBasedDLQ() {
        try {
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
                return;
            }

            try (Stream<Path> files = Files.list(logDir)) {
                files.forEach(this::processLogFile);
            }
        } catch (IOException e) {
            log.error("Error reading or creating log directory: {}", e.getMessage());
        }
    }

    /**
     * Processes a single log file, retrying the event and deleting the file on success.
     */
    protected void processLogFile(Path file) {
        try {
            Map<String, Object> logEntry = objectMapper.readValue(file.toFile(), Map.class);
            String eventId = (String) logEntry.get("eventId");
            if (eventId == null) {
                log.error("Found log entry without eventId: {}", file);
                return;
            }

            if (processedEvents.contains(eventId)) {
                log.info("Skipping duplicate log event {}", eventId);
                deleteLogFile(file, eventId);
                return;
            }

            OrderEvent event = reconstructEvent(logEntry);
            String topic = (String) logEntry.get("topic");

            redisStatusChecker.isRedisAvailable()
                    .flatMap(available -> {
                        if (!available) {
                            log.info("Redis still unavailable, skipping log event retry for {}", eventId);
                            return Mono.empty();
                        }
                        return eventLogger.logEvent(event, topic, new RuntimeException("Redis retry"))
                                .doOnSuccess(result -> {
                                    addProcessedEvent(eventId);
                                    log.info("Successfully retried log event {}", eventId);
                                    deleteLogFile(file, eventId);
                                })
                                .doOnError(e -> log.error("Failed to retry log event {}: {}", eventId, e.getMessage()));
                    })
                    .subscribe();
        } catch (IOException e) {
            log.error("Error parsing log file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Increments a Micrometer counter with the specified tags.
     */
    protected void incrementCounter(String counterName, Map<String, String> tags) {
        meterRegistry.counter(counterName,
                "eventType", tags.get("eventType"),
                "step", tags.get("step"),
                "topic", tags.get("topic")).increment();
    }

    /**
     * Adds an event ID to the processed events set with a timestamp.
     */
    protected void addProcessedEvent(String eventId) {
        if (eventId == null) {
            return;
        }
        processedEvents.add(eventId);
        eventTimestamps.put(eventId, System.currentTimeMillis());
        cleanOldEvents();
    }

    /**
     * Cleans old events from processedEvents to prevent memory leaks.
     * Optimized to run in O(n) time where n is the number of expired entries.
     */
    protected void cleanOldEvents() {
        long expirationTimeMillis = System.currentTimeMillis() - Duration.ofHours(dlqConfig.getEventExpirationHours()).toMillis();

        // More efficient removal by using entrySet and removeIf
        eventTimestamps.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue() < expirationTimeMillis;
            if (expired) {
                processedEvents.remove(entry.getKey());
            }
            return expired;
        });
    }

    /**
     * Deletes a log file and logs the result.
     */
    protected void deleteLogFile(Path file, String eventId) {
        Mono.fromRunnable(() -> {
            try {
                Files.delete(file);
                log.info("Deleted log file {} for event {}", file.getFileName(), eventId);
            } catch (IOException e) {
                log.error("Failed to delete log file {} for event {}: {}", file.getFileName(), eventId, e.getMessage());
            }
        }).subscribe();
    }
}