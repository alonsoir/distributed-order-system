package com.example.order.service;

import com.example.order.events.OrderEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component("orderEventPublisher")// Nombre único, en principio, creo que solo necesito esta implementacion.
@RequiredArgsConstructor
public class EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final String REDIS_SUCCESS_COUNTER = "redis_success";
    private static final String REDIS_FAILURE_COUNTER = "redis_failure";
    private static final String REDIS_RETRY_COUNTER = "redis_retry";
    private static final String OUTBOX_SUCCESS_COUNTER = "outbox_success";
    private static final String OUTBOX_FAILURE_COUNTER = "outbox_failure";
    private static final String OUTBOX_RETRY_COUNTER = "outbox_retry";
    private static final String DLQ_SUCCESS_COUNTER = "dead_letter_queue_success";
    private static final String DLQ_FAILURE_COUNTER = "dead_letter_queue_failure";
    private static final String REDIS_TIMER = "event.publish.redis.timer";
    private static final String OUTBOX_TIMER = "event.publish.outbox.timer";

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final DatabaseClient databaseClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry meterRegistry;

    public Mono<OrderEvent> publishEvent(OrderEvent event, String stepName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisEventPublishing");
        MDC.put("correlationId", event.getCorrelationId());
        MDC.put("orderId", String.valueOf(event.getOrderId()));
        return Mono.defer(() -> {
                    Map<String, Object> eventMap = buildEventMap(event);
                    Timer.Sample sample = Timer.start();
                    return redisTemplate.opsForStream()
                            .add("orders", eventMap)
                            .doOnSuccess(record -> {
                                log.info("Published event {} for order {}", event.getType().name(), event.getOrderId());
                                meterRegistry.counter(REDIS_SUCCESS_COUNTER).increment();
                            })
                            .doOnError(e -> {
                                log.error("Failed to publish event {} for order {}: {}", event.getType().name(), event.getOrderId(), e.getMessage());
                                meterRegistry.counter(REDIS_FAILURE_COUNTER).increment();
                            })
                            .doFinally(signalType -> {
                                Timer timer = meterRegistry.timer(REDIS_TIMER);
                                sample.stop(timer);
                            })
                            .thenReturn(event);
                })
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(throwable -> throwable instanceof RedisException)
                        .doBeforeRetry(signal -> {
                            log.warn("Retrying Redis publish for event {} (attempt {})", event.getType().name(), signal.totalRetries() + 1);
                            meterRegistry.counter(REDIS_RETRY_COUNTER).increment();
                        }))
                .transform(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(throwable -> handleRedisFailure(event, throwable, stepName))
                .doFinally(signalType -> MDC.clear());
    }

    private Mono<OrderEvent> handleRedisFailure(OrderEvent event, Throwable throwable, String stepName) {
        log.error("Redis failure for event {}: {}", event.getType().name(), throwable.getMessage());
        return persistToOutbox(event, stepName)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(error -> error instanceof io.r2dbc.spi.R2dbcException)
                        .doBeforeRetry(signal -> {
                            log.warn("Retrying Outbox persist for event {} (attempt {})", event.getType().name(), signal.totalRetries() + 1);
                            meterRegistry.counter(OUTBOX_RETRY_COUNTER).increment();
                        }))
                .thenReturn(event)
                .onErrorResume(outboxError -> pushToDeadLetterQueue(event, outboxError, stepName).thenReturn(event));
    }

    private Mono<Void> persistToOutbox(OrderEvent event, String stepName) {
        Timer.Sample sample = Timer.start();
        return databaseClient
                .sql("CALL insert_outbox(:event_type, :correlationId, :eventId, :payload)")
                .bind("event_type", event.getType().name())
                .bind("correlationId", event.getCorrelationId())
                .bind("eventId", event.getEventId())
                .bind("payload", event.toJson())
                .then()
                .doOnSuccess(v -> {
                    meterRegistry.counter(OUTBOX_SUCCESS_COUNTER).increment();
                    log.info("Persisted event {} to outbox for order {}", event.getType().name(), event.getOrderId());
                })
                .doOnError(e -> {
                    meterRegistry.counter(OUTBOX_FAILURE_COUNTER).increment();
                    log.error("Failed to persist event {} to outbox for order {}: {}", event.getType().name(), event.getOrderId(), e.getMessage());
                })
                .doFinally(st -> {
                    Timer timer = meterRegistry.timer(OUTBOX_TIMER);
                    sample.stop(timer);
                });
    }

    private Mono<Void> pushToDeadLetterQueue(OrderEvent event, Throwable error, String stepName) {
        Map<String, Object> failedEvent = buildEventMap(event);
        failedEvent.put("error", error.getMessage());
        failedEvent.put("timestamp", System.currentTimeMillis());
        failedEvent.put("retries", 0);

        return redisTemplate.opsForList()
                .leftPush("failed-outbox-events", failedEvent)
                .then()
                .doOnSuccess(v -> {
                    meterRegistry.counter(DLQ_SUCCESS_COUNTER).increment();
                    log.info("Pushed failed event {} to dead-letter queue for order {}", event.getType().name(), event.getOrderId());
                })
                .doOnError(e -> {
                    meterRegistry.counter(DLQ_FAILURE_COUNTER).increment();
                    log.error("Failed to push event {} to dead-letter queue for order {}: {}", event.getType().name(), event.getOrderId(), e.getMessage());
                });
    }

    private Map<String, Object> buildEventMap(OrderEvent event) {
        if (event.getType() == null) {
            throw new IllegalArgumentException("Event type cannot be null");
        }
        if (event.getOrderId() == null) {
            throw new IllegalArgumentException("Order ID cannot be null");
        }
        if (event.getCorrelationId() == null) {
            throw new IllegalArgumentException("Correlation ID cannot be null");
        }
        if (event.getEventId() == null) {
            throw new IllegalArgumentException("Event ID cannot be null");
        }
        Map<String, Object> eventMap = new HashMap<>();
        eventMap.put("orderId", event.getOrderId());
        eventMap.put("correlationId", event.getCorrelationId());
        eventMap.put("eventId", event.getEventId());
        eventMap.put("type", event.getType().name());
        eventMap.put("payload", event.toJson());
        return eventMap;
    }
}