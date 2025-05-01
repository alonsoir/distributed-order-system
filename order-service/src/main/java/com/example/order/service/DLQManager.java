package com.example.order.service;

import com.example.order.events.DefaultOrderEvent;
import com.example.order.events.OrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.validation.constraints.Min;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages the Dead Letter Queue (DLQ) for failed order events, storing them in Redis
 * and providing a fallback to log-based recovery when Redis is unavailable.
 */
@Component
@RequiredArgsConstructor
public class DLQManager {
    private static final Logger log = LoggerFactory.getLogger(DLQManager.class);
    private static final String DLQ_SUCCESS_COUNTER = "dead_letter_queue_success";
    private static final String DLQ_FAILURE_COUNTER = "dead_letter_queue_failure";
    private static final Path LOG_DIR = Paths.get("failed-events");
    private static final String DLQ_KEY = "failed-outbox-events";

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RedisStatusChecker redisStatusChecker;
    private final EventLogger eventLogger;
    private final DLQConfig dlqConfig;
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> eventTimestamps = new ConcurrentHashMap<>();

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
    }

    /**
     * Pushes a failed event to the DLQ in Redis with retry logic.
     *
     * @param event    the failed event
     * @param error    the error that caused the failure
     * @param stepName the processing step where the failure occurred
     * @param topic    the target topic for the event
     * @return a Mono indicating completion
     */
    public Mono<Void> pushToDLQ(OrderEvent event, Throwable error, String stepName, String topic) {
        Map<String, Object> failedEvent = new HashMap<>();
        failedEvent.put("eventId", event.getEventId());
        failedEvent.put("correlationId", event.getCorrelationId());
        failedEvent.put("orderId", event.getOrderId());
        failedEvent.put("type", event.getType().name());
        failedEvent.put("topic", topic);
        failedEvent.put("payload", event.toJson());
        failedEvent.put("error", error.getMessage());
        failedEvent.put("timestamp", System.currentTimeMillis());
        failedEvent.put("retries", 0);

        Map<String, String> tags = Map.of(
                "eventType", event.getType().name(),
                "step", stepName,
                "topic", topic
        );

        return redisTemplate.opsForList()
                .leftPush(DLQ_KEY, failedEvent)
                .then()
                .doOnSuccess(v -> {
                    incrementCounter(DLQ_SUCCESS_COUNTER, tags);
                    log.info("Pushed event {} to DLQ for topic {} and order {}", event.getType().name(), topic, event.getOrderId());
                    addProcessedEvent(event.getEventId());
                })
                .doOnError(e -> {
                    incrementCounter(DLQ_FAILURE_COUNTER, tags);
                    log.error("Failed to push event {} to DLQ for topic {}: {}", event.getType().name(), topic, e.getMessage());
                })
                .retryWhen(Retry.backoff(dlqConfig.getRetryMaxAttempts(), Duration.ofSeconds(dlqConfig.getBackoffSeconds()))
                        .filter(t -> t instanceof RedisException));
    }

    /**
     * Periodically processes events in the DLQ, retrying them or moving them to log-based recovery.
     */
    @Scheduled(fixedDelayString = "${dlq.process.interval:60000}")
    public void processDLQ() {
        redisStatusChecker.isRedisAvailable()
                .flatMap(available -> {
                    if (!available) {
                        log.warn("Redis is unavailable, skipping DLQ processing");
                        processLogBasedDLQ();
                        return Mono.empty();
                    }
                    return redisTemplate.opsForList()
                            .rightPop(DLQ_KEY)
                            .flatMap(obj -> {
                                if (!(obj instanceof Map)) {
                                    log.error("Invalid DLQ entry, expected Map, got {}", obj.getClass());
                                    return Mono.empty();
                                }
                                return retryEvent((Map<String, Object>) obj);
                            })
                            .doOnError(e -> log.error("Error processing DLQ: {}", e.getMessage()));
                })
                .subscribe();
    }

    /**
     * Retries a failed event from the DLQ or moves it to log-based recovery if max retries are reached.
     *
     * @param failedEvent the event data from the DLQ
     * @return a Mono indicating completion
     */
    private Mono<Void> retryEvent(Map<String, Object> failedEvent) {
        String eventId = (String) failedEvent.get("eventId");
        if (processedEvents.contains(eventId)) {
            log.info("Skipping duplicate event {} in DLQ", eventId);
            return Mono.empty();
        }

        return validateAndRetryEvent(failedEvent, eventId);
    }

    /**
     * Validates and retries an event, handling retries and publishing.
     *
     * @param failedEvent the event data
     * @param eventId     the event identifier
     * @return a Mono indicating completion
     */
    private Mono<Void> validateAndRetryEvent(Map<String, Object> failedEvent, String eventId) {
        OrderEvent event = reconstructEvent(failedEvent);
        String topic = (String) failedEvent.get("topic");
        int retries = ((Number) failedEvent.get("retries")).intValue();

        if (retries >= dlqConfig.getRetryMaxAttempts()) {
            log.warn("Max retries reached for event {}, moving to log-based recovery", eventId);
            return eventLogger.logEvent(event, topic, new RuntimeException("Max retries reached"));
        }

        failedEvent.put("retries", retries + 1);
        return eventPublisher.publishEvent(event, "dlq-retry", topic)
                .doOnSuccess(result -> {
                    if (result.isSuccess()) {
                        addProcessedEvent(eventId);
                        log.info("Successfully retried event {} from DLQ", eventId);
                    }
                })
                .doOnError(e -> log.error("Failed to retry event {}: {}", eventId, e.getMessage()))
                .then();
    }

    /**
     * Processes events from log-based DLQ when Redis is unavailable.
     */
    private void processLogBasedDLQ() {
        try (Stream<Path> files = Files.list(LOG_DIR)) {
            files.forEach(this::processLogFile);
        } catch (IOException e) {
            log.error("Error reading log directory: {}", e.getMessage());
        }
    }

    /**
     * Processes a single log file, retrying the event and deleting the file on success.
     *
     * @param file the log file to process
     */
    private void processLogFile(Path file) {
        try {
            Map<String, Object> logEntry = objectMapper.readValue(file.toFile(), Map.class);
            String eventId = (String) logEntry.get("eventId");
            if (processedEvents.contains(eventId)) {
                log.info("Skipping duplicate log event {}", eventId);
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
                        return eventPublisher.publishEvent(event, "log-retry", topic)
                                .doOnSuccess(result -> {
                                    if (result.isSuccess()) {
                                        addProcessedEvent(eventId);
                                        log.info("Successfully retried log event {}", eventId);
                                        deleteLogFile(file, eventId);
                                    }
                                })
                                .doOnError(e -> log.error("Failed to retry log event {}: {}", eventId, e.getMessage()));
                    })
                    .subscribe();
        } catch (IOException e) {
            log.error("Error parsing log file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Reconstructs an OrderEvent from stored data.
     *
     * @param data the event data
     * @return the reconstructed OrderEvent
     * @throws RuntimeException if reconstruction fails
     */
    private OrderEvent reconstructEvent(Map<String, Object> data) {
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
     * Increments a Micrometer counter with the specified tags.
     *
     * @param counterName the counter name
     * @param tags        the tags for the counter
     */
    private void incrementCounter(String counterName, Map<String, String> tags) {
        meterRegistry.counter(counterName,
                "eventType", tags.get("eventType"),
                "step", tags.get("step"),
                "topic", tags.get("topic")).increment();
    }

    /**
     * Adds an event ID to the processed events set with a timestamp.
     *
     * @param eventId the event ID
     */
    private void addProcessedEvent(String eventId) {
        processedEvents.add(eventId);
        eventTimestamps.put(eventId, System.currentTimeMillis());
        cleanOldEvents();
    }

    /**
     * Cleans old events from processedEvents to prevent memory leaks.
     */
    private void cleanOldEvents() {
        long threshold = System.currentTimeMillis() - Duration.ofHours(24).toMillis();
        eventTimestamps.entrySet().removeIf(entry -> {
            if (entry.getValue() < threshold) {
                processedEvents.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Deletes a log file and logs the result.
     *
     * @param file    the file to delete
     * @param eventId the associated event ID
     */
    private void deleteLogFile(Path file, String eventId) {
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