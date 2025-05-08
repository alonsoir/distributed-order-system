package com.example.order.service;

import com.example.order.events.OrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Paths;
import java.util.Map;

/**
 * Implementation of DLQManager that manages the dead letter queue for failed order events.
 */
@Component
public class DLQManagerImpl extends AbstractDLQManager implements DLQManager {
    private static final Logger log = LoggerFactory.getLogger(DLQManagerImpl.class);

    /**
     * Constructor with all required components.
     */
    public DLQManagerImpl(
            ReactiveRedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            RedisStatusChecker redisStatusChecker,
            EventLogger eventLogger,
            DLQConfig dlqConfig,
            @Value("${dlq.log.dir:failed-events}") String logDirPath) {
        super(redisTemplate, objectMapper, meterRegistry, redisStatusChecker, eventLogger, dlqConfig, Paths.get(logDirPath));
    }

    /**
     * Pushes a failed event to the DLQ in Redis with retry logic.
     * Implementación del método de la interfaz.
     */
    @Override
    public Mono<EventPublishOutcome<OrderEvent>> pushToDLQ(OrderEvent event, Throwable error, String stepName, String topic) {
        if (event == null) {
            log.error("Cannot push null event to DLQ");
            return Mono.just(EventPublishOutcome.dlqFailure(null, new IllegalArgumentException("Cannot push null event to DLQ")));
        }

        Map<String, Object> failedEvent = createDLQEntry(event, error, topic);
        Map<String, String> metricTags = createMetricTags(event, stepName, topic);

        return redisTemplate.opsForList()
                .leftPush(DLQ_KEY, failedEvent)
                .then(Mono.just(EventPublishOutcome.dlq(event, error)))
                .doOnSuccess(v -> handlePushSuccess(event, topic, metricTags))
                .doOnError(e -> handlePushError(metricTags))
                .onErrorResume(e -> handlePushErrorWithFallback(event, topic, error, e))
                .retryWhen(createRetrySpec());
    }

    /**
     * Periodically processes events in the DLQ, retrying them or moving them to log-based recovery.
     * This is scheduled but also exposed for direct invocation in tests.
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
                    return processNextDLQItem();
                })
                .subscribe();
    }
}