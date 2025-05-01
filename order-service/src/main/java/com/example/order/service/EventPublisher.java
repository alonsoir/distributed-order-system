package com.example.order.service;

import com.example.order.events.OrderEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.lettuce.core.RedisException;
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

    public Mono<Result<OrderEvent>> publishEvent(OrderEvent event, String step, String topic) {
        return redisTemplate.opsForStream()
                .add(topic, Map.of("payload", event.toJson()))
                .map(recordId -> Result.success(event))
                .onErrorResume(RedisException.class, e -> {
                    log.error("Redis failure for event {}: {}", event.getEventId(), e.getMessage());
                    return persistToOutbox(event, topic)
                            .thenReturn(Result.outbox(event));
                })
                .onErrorResume(e -> dlqManager.pushToDLQ(event, e, step, topic)
                        .thenReturn(Result.dlq(event, e)));
    }

    private Mono<Void> persistToOutbox(OrderEvent event, String topic) {
        return databaseClient.sql("CALL insert_outbox(:type, :correlationId, :eventId, :payload, :topic)")
                .bind("type", event.getType().name())
                .bind("correlationId", event.getCorrelationId())
                .bind("eventId", event.getEventId())
                .bind("payload", event.toJson())
                .bind("topic", topic)
                .then();
    }
}