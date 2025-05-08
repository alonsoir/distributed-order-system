package com.example.order.service;

import com.example.order.model.SagaStep;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class CompensationManagerImpl implements CompensationManager {
    private static final Logger log = LoggerFactory.getLogger(CompensationManagerImpl.class);
    private static final String COMPENSATION_RETRY_COUNTER = "saga_compensation_retry";
    private static final String COMPENSATION_TIMER = "saga_compensation_timer";
    private static final String COMPENSATION_DLQ_KEY = "failed-compensations";

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    // Método protegido que puede ser sobrescrito en pruebas
    protected Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    // Método protegido que puede ser sobrescrito en pruebas
    protected void stopTimer(Timer.Sample sample, Timer timer) {
        sample.stop(timer);
    }

    @Override
    public Mono<Void> executeCompensation(SagaStep step) {
        if (step == null) {
            return Mono.error(new IllegalArgumentException("SagaStep cannot be null"));
        }
        if (step.getCompensation() == null) {
            return Mono.error(new IllegalArgumentException("Compensation cannot be null for step: " +
                    (step.getName() != null ? step.getName() : "unknown")));
        }

        // Enriquecemos el contexto de diagnóstico
        Map<String, String> diagnosticContext = Map.of(
                "compensationStep", step.getName(),
                "orderId", String.valueOf(step.getOrderId()),
                "correlationId", step.getCorrelationId(),
                "eventId", step.getEventId()
        );

        Timer.Sample compensationTimer = startTimer();

        return withDiagnosticContext(diagnosticContext, () -> {
            log.info("Executing compensation for step {} of order {}", step.getName(), step.getOrderId());

            return step.getCompensation().get()
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                            .filter(throwable -> throwable instanceof io.lettuce.core.RedisException)
                            .doBeforeRetry(signal -> {
                                log.warn("Retrying compensation for step {} (attempt {})",
                                        step.getName(), signal.totalRetries() + 1);
                                meterRegistry.counter(COMPENSATION_RETRY_COUNTER,
                                        "step", step.getName(),
                                        "attempt", String.valueOf(signal.totalRetries() + 1)).increment();
                            }))
                    .doOnSuccess(c -> {
                        stopTimer(compensationTimer, meterRegistry.timer(COMPENSATION_TIMER,
                                "step", step.getName(),
                                "status", "success"));
                        log.info("Compensation {} executed successfully for order {}",
                                step.getName(), step.getOrderId());
                    })
                    .doOnError(compE -> {
                        stopTimer(compensationTimer, meterRegistry.timer(COMPENSATION_TIMER,
                                "step", step.getName(),
                                "status", "failed",
                                "error_type", compE.getClass().getSimpleName()));
                        log.error("Compensation {} failed for order {}: {} - {}",
                                step.getName(), step.getOrderId(),
                                compE.getClass().getSimpleName(), compE.getMessage(), compE);
                    })
                    .then()
                    .onErrorResume(compE -> {
                        CompensationTask failedTask = new CompensationTask(
                                step.getOrderId(),
                                step.getCorrelationId(),
                                String.format("%s: %s", compE.getClass().getSimpleName(), compE.getMessage()),
                                0);

                        return redisTemplate.opsForList()
                                .leftPush(COMPENSATION_DLQ_KEY, failedTask)
                                .doOnSuccess(v -> log.info("Pushed failed compensation task to DLQ for order {}, step {}",
                                        step.getOrderId(), step.getName()))
                                .doOnError(dlqE -> log.error("Failed to push compensation task to DLQ for order {}, step {}: {}",
                                        step.getOrderId(), step.getName(), dlqE.getMessage(), dlqE))
                                .then(Mono.error(compE));
                    });
        });
    }

    /**
     * Helper method para enriquecer el contexto de diagnóstico
     */
    private <T> Mono<T> withDiagnosticContext(Map<String, String> context, Supplier<Mono<T>> operation) {
        return Mono.fromCallable(() -> {
                    context.forEach(MDC::put);
                    return true;
                })
                .flatMap(result -> operation.get())
                .doFinally(signal -> MDC.clear());
    }
}