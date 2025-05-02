package com.example.order.service;

import com.example.order.events.OrderEvent;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final DatabaseClient databaseClient;
    private final MeterRegistry meterRegistry;
    private final DLQManager dlqManager;

    private static final String METRIC_PREFIX = "order.event.publisher";

    public Mono<EventPublishOutcome<OrderEvent>> publishEvent(OrderEvent event, String step, String topic) {
        if (event == null || topic == null || topic.isEmpty() || step == null || step.isEmpty()) {
            log.error("Invalid input: event={}, topic={}, step={}", event, topic, step);
            Throwable error = new IllegalArgumentException("Event, topic, and step must not be null or empty");
            meterRegistry.counter(METRIC_PREFIX + ".dlq", "step", step != null ? step : "unknown", "topic", topic != null ? topic : "unknown").increment();
            return dlqManager.pushToDLQ(event, error, step, topic);
        }

        if (event.getType() == null || event.getEventId() == null || event.getCorrelationId() == null) {
            log.error("Invalid event fields: type={}, eventId={}, correlationId={}", event.getType(), event.getEventId(), event.getCorrelationId());
            Throwable error = new IllegalArgumentException("Event type, eventId, and correlationId must not be null");
            meterRegistry.counter(METRIC_PREFIX + ".dlq", "step", step, "topic", topic).increment();
            return dlqManager.pushToDLQ(event, error, step, topic);
        }

        String json = event.toJson();
        if (json == null) {
            log.error("Event {} produced null JSON", event.getEventId());
            Throwable error = new IllegalStateException("Null JSON from event");
            meterRegistry.counter(METRIC_PREFIX + ".dlq", "step", step, "topic", topic).increment();
            return dlqManager.pushToDLQ(event, error, step, topic);
        }

        return redisTemplate.opsForStream()
                .add(topic, Map.of("payload", json))
                .doOnSuccess(recordId -> meterRegistry.counter(METRIC_PREFIX + ".success", "step", step, "topic", topic).increment())
                .map(recordId -> EventPublishOutcome.success(event))
                .onErrorResume(RedisException.class, e -> {
                    log.error("Redis failure for event {} on topic {} at step {}: {}", event.getEventId(), topic, step, e.getMessage(), e);
                    meterRegistry.counter(METRIC_PREFIX + ".outbox", "step", step, "topic", topic).increment();
                    return persistToOutbox(event, topic)
                            .thenReturn(EventPublishOutcome.outbox(event))
                            .onErrorResume(dbError -> {
                                log.error("Outbox persist failed for event {}: {}", event, dbError.getMessage(), dbError);
                                meterRegistry.counter(METRIC_PREFIX + ".dlq", "step", step, "topic", topic).increment();
                                return dlqManager.pushToDLQ(event, dbError, step, topic);
                            });
                })
                .onErrorResume(e -> {
                    log.error("Unexpected error for event {} on topic {} at step {}: {}", event.getEventId(), topic, step, e.getMessage(), e);
                    meterRegistry.counter(METRIC_PREFIX + ".dlq", "step", step, "topic", topic).increment();
                    return dlqManager.pushToDLQ(event, e, step, topic);
                });
    }

    private Mono<Void> persistToOutbox(OrderEvent event, String topic) {
        // Additional null check to prevent binding issues
        if (event == null || event.getType() == null || event.getEventId() == null || event.getCorrelationId() == null) {
            log.error("Invalid event for outbox: event={}, type={}, eventId={}, correlationId={}",
                    event, event != null ? event.getType() : null, event != null ? event.getEventId() : null,
                    event != null ? event.getCorrelationId() : null);
            return Mono.error(new IllegalArgumentException("Event and its required fields must not be null for outbox"));
        }

        String payload = event.toJson();
        if (payload == null) {
            log.error("Null JSON for event {} in outbox", event.getEventId());
            return Mono.error(new IllegalStateException("Null JSON for outbox"));
        }

        return databaseClient.sql("CALL insert_outbox(:type, :correlationId, :eventId, :payload, :topic)")
                .bind("type", event.getType())
                .bind("correlationId", event.getCorrelationId())
                .bind("eventId", event.getEventId())
                .bind("payload", payload)
                .bind("topic", topic)
                .then();
    }
}