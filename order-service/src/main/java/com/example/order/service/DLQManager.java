package com.example.order.service;

import com.example.order.events.DefaultOrderEvent;
import com.example.order.events.OrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

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
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();

    @Value("${dlq.retry.max-attempts:3}")
    private long dlqRetryAttempts;
    @Value("${dlq.retry.backoff.seconds:1}")
    private long dlqBackoffSeconds;

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
                    meterRegistry.counter(DLQ_SUCCESS_COUNTER, "eventType", tags.get("eventType"), "step", tags.get("step"), "topic", tags.get("topic")).increment();
                    log.info("Pushed event {} to DLQ for topic {} and order {}", event.getType().name(), topic, event.getOrderId());
                    processedEvents.add(event.getEventId());
                })
                .doOnError(e -> {
                    meterRegistry.counter(DLQ_FAILURE_COUNTER, "eventType", tags.get("eventType"), "step", tags.get("step"), "topic", tags.get("topic")).increment();
                    log.error("Failed to push event {} to DLQ for topic {}: {}", event.getType().name(), topic, e.getMessage());
                })
                .retryWhen(Retry.backoff(dlqRetryAttempts, Duration.ofSeconds(dlqBackoffSeconds))
                        .filter(t -> t instanceof RedisException));
    }

    @Scheduled(fixedDelayString = "${dlq.process.interval:60000}")
    public void processDLQ() {
        if (!redisStatusChecker.isRedisAvailable().block()) {
            log.warn("Redis is unavailable, skipping DLQ processing");
            processLogBasedDLQ();
            return;
        }

        redisTemplate.opsForList()
                .rightPop(DLQ_KEY)
                .flatMap(obj -> retryEvent((Map<String, Object>) obj))
                .doOnError(e -> log.error("Error processing DLQ: {}", e.getMessage()))
                .subscribe();
    }

    private Mono<Void> retryEvent(Map<String, Object> failedEvent) {
        String eventId = (String) failedEvent.get("eventId");
        if (processedEvents.contains(eventId)) {
            log.info("Skipping duplicate event {} in DLQ", eventId);
            return Mono.empty();
        }

        OrderEvent event = reconstructEvent(failedEvent);
        String topic = (String) failedEvent.get("topic");
        int retries = ((Number) failedEvent.get("retries")).intValue();

        if (retries >= dlqRetryAttempts) {
            log.warn("Max retries reached for event {}, moving to log-based recovery", eventId);
            return eventLogger.logEvent(event, topic, new RuntimeException("Max retries reached"));
        }

        failedEvent.put("retries", retries + 1);
        return eventPublisher.publishEvent(event, "dlq-retry", topic)
                .doOnSuccess(result -> {
                    if (result.isSuccess()) {
                        processedEvents.add(eventId);
                        log.info("Successfully retried event {} from DLQ", eventId);
                    }
                })
                .doOnError(e -> log.error("Failed to retry event {}: {}", eventId, e.getMessage()))
                .then();
    }

    private void processLogBasedDLQ() {
        try (Stream<Path> files = Files.list(LOG_DIR)) {
            files.forEach(file -> {
                try {
                    processLogFile(file);
                } catch (IOException e) {
                    log.error("Error processing log file {}: {}", file, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("Error reading log directory: {}", e.getMessage());
        }
    }

    private void processLogFile(Path file) throws IOException {
        Map<String, Object> logEntry = objectMapper.readValue(file.toFile(), Map.class);
        String eventId = (String) logEntry.get("eventId");
        if (processedEvents.contains(eventId)) {
            log.info("Skipping duplicate log event {}", eventId);
            return;
        }

        OrderEvent event = reconstructEvent(logEntry);
        String topic = (String) logEntry.get("topic");

        if (redisStatusChecker.isRedisAvailable().block()) {
            eventPublisher.publishEvent(event, "log-retry", topic)
                    .doOnSuccess(result -> {
                        if (result.isSuccess()) {
                            processedEvents.add(eventId);
                            Files.delete(file);
                            log.info("Successfully retried log event {} and deleted file", eventId);
                        }
                    })
                    .doOnError(e -> log.error("Failed to retry log event {}: {}", eventId, e.getMessage()))
                    .subscribe();
        }
    }

    private OrderEvent reconstructEvent(Map<String, Object> data) {
        String json = (String) data.get("payload");
        return DefaultOrderEvent.fromJson(json);
    }
}