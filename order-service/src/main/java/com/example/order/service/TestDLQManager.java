package com.example.order.service;

import com.example.order.events.OrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.util.Map;

/**
 * Clase especial para pruebas que expone el método protegido.
 */
public class TestDLQManager extends DLQManagerImpl {

    public TestDLQManager(
            ReactiveRedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            RedisStatusChecker redisStatusChecker,
            EventLogger eventLogger,
            DLQConfig dlqConfig,
            String logDirPath) {
        super(redisTemplate, objectMapper, meterRegistry, redisStatusChecker, eventLogger, dlqConfig, logDirPath);
    }

    // Exponemos el método protegido para testing
    @Override
    public OrderEvent reconstructEvent(Map<String, Object> data) {
        return super.reconstructEvent(data);
    }
}