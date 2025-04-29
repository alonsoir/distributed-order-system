package com.example.order.service;

import com.example.order.model.SagaStep;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class CompensationManager {
    private static final Logger log = LoggerFactory.getLogger(CompensationManager.class);
    private static final String COMPENSATION_RETRY_COUNTER = "saga_compensation_retry";

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    public Mono<Void> executeCompensation(SagaStep step) {
        if (step == null) {
            throw new IllegalArgumentException("SagaStep cannot be null");
        }
        if (step.getCompensation() == null) {
            throw new IllegalArgumentException("Compensation cannot be null for step: " + step.getName());
        }
        MDC.put("correlationId", step.getCorrelationId());
        MDC.put("orderId", String.valueOf(step.getOrderId()));
        return step.getCompensation().get()
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(throwable -> throwable instanceof io.lettuce.core.RedisException)
                        .doBeforeRetry(signal -> {
                            log.warn("Retrying compensation for step {} (attempt {})", step.getName(), signal.totalRetries() + 1);
                            meterRegistry.counter(COMPENSATION_RETRY_COUNTER).increment();
                        }))
                .doOnSuccess(c -> log.info("Compensation {} executed for order {}", step.getName(), step.getOrderId()))
                .then()
                .onErrorResume(compE -> {
                    log.error("Compensation {} failed for order {}: {}", step.getName(), step.getOrderId(), compE.getMessage());
                    return redisTemplate.opsForList()
                            .leftPush("failed-compensations",
                                    // public record CompensationTask(Long orderId, String correlationId, String errorMessage, int retries) {}
                                    new CompensationTask(step.getOrderId(), step.getCorrelationId(), compE.getMessage(), 0))
                            .then(Mono.error(compE));
                })
                .doFinally(signalType -> MDC.clear());
    }
}