package com.example.order.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

@Component
@EnableScheduling
@RequiredArgsConstructor
public class OutboxMonitor {
    private static final Logger log = LoggerFactory.getLogger(OutboxMonitor.class);
    private static final int MAX_RETRIES = 10;

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final DatabaseClient databaseClient;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelay = 60000)
    public void processFailedOutboxEvents() {
        redisTemplate.opsForList()
                .rightPop("failed-outbox-events")
                .flatMap(eventObj -> {
                    if (!(eventObj instanceof Map)) {
                        log.error("Invalid event format in failed-outbox-events: {}", eventObj.getClass().getName());
                        return Mono.empty();
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = (Map<String, Object>) eventObj;
                    String eventType = Objects.toString(event.get("type"));
                    String correlationId = Objects.toString(event.get("correlationId"));
                    String eventId = Objects.toString(event.get("eventId"));
                    String payload = Objects.toString(event.get("payload"));
                    Long orderId = event.get("orderId") != null ? ((Number) event.get("orderId")).longValue() : null;
                    int retries = event.get("retries") != null ? ((Number) event.get("retries")).intValue() : 0;

                    if (retries >= MAX_RETRIES) {
                        log.error("Event {} exceeded retry limit ({}) for order {}", eventType, MAX_RETRIES, orderId);
                        return Mono.empty();
                    }

                    return databaseClient
                            .sql("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)")
                            .bind("event_type", eventType)
                            .bind("correlationId", correlationId)
                            .bind("eventId", eventId)
                            .bind("payload", payload)
                            .then()
                            .doOnSuccess(v -> {
                                meterRegistry.counter("dead_letter_queue_success").increment();
                                log.info("Reprocessed event {} for order {}", eventType, orderId);
                            })
                            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))
                            .onErrorResume(e -> {
                                event.put("retries", retries + 1);
                                return redisTemplate.opsForList()
                                        .leftPush("failed-outbox-events", event)
                                        .doOnSuccess(v -> log.info("Requeued event {} for order {}", eventType, orderId))
                                        .then(Mono.empty());
                            });
                })
                .doOnError(e -> log.error("Error processing failed-outbox-events: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    @Scheduled(fixedRate = 300000)
    public void monitorFailedOutboxQueue() {
        redisTemplate.opsForList()
                .size("failed-outbox-events")
                .subscribe(size -> meterRegistry.gauge("failed_outbox_events_queue_length", size));
    }
}