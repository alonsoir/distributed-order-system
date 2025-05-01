package com.example.order.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;

@Component
@RequiredArgsConstructor
public class StreamTrimmer {
    private static final Logger log = LoggerFactory.getLogger(StreamTrimmer.class);
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final RedisStatusChecker redisStatusChecker;

    @Value("${redis.stream.min-size:1000}")
    private long minStreamSize;
    @Value("${redis.stream.max-size:10000}")
    private long maxStreamSize;
    @Value("${redis.stream.default-size:5000}")
    private long defaultStreamSize;

    private volatile long currentStreamSize = defaultStreamSize;

    @Scheduled(fixedDelayString = "${redis.trim.interval:60000}")
    public void trimStreams() {
        if (Boolean.FALSE.equals(redisStatusChecker.isRedisAvailable().block())) {
            log.warn("Redis is unavailable, skipping stream trimming");
            return;
        }

        adjustStreamSize();
        redisTemplate.opsForStream()
                .trim("orders", currentStreamSize)
                .doOnSuccess(count -> log.info("Trimmed orders stream to {} entries", count))
                .doOnError(e -> log.error("Error trimming stream: {}", e.getMessage()))
                .subscribe();
    }

    private void adjustStreamSize() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        double cpuLoad = osBean.getSystemLoadAverage();
        long usedHeap = memoryBean.getHeapMemoryUsage().getUsed();
        long maxHeap = memoryBean.getHeapMemoryUsage().getMax();

        double memoryUsageRatio = (double) usedHeap / maxHeap;

        // Adjust stream size based on CPU and memory
        if (cpuLoad < 0.5 && memoryUsageRatio < 0.5) {
            currentStreamSize = Math.min(currentStreamSize + 1000, maxStreamSize);
        } else if (cpuLoad > 0.8 || memoryUsageRatio > 0.8) {
            currentStreamSize = Math.max(currentStreamSize - 1000, minStreamSize);
        }

        // Expose current stream size as a metric
        Gauge.builder("redis.stream.size", () -> currentStreamSize)
                .description("Current Redis stream size")
                .register(meterRegistry);

        log.debug("Adjusted stream size to {}", currentStreamSize);
    }
}