package com.example.order.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

@SpringBootTest
@Testcontainers
class RedisIntegrationTest {

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Test
    void shouldPublishEventToRedisStream() {
        Map<String, Object> eventMap = new HashMap<>();
        eventMap.put("orderId", 1L);
        eventMap.put("correlationId", "corr-123");
        eventMap.put("eventId", "event-123");
        eventMap.put("type", "OrderCreated");
        eventMap.put("payload", "{\"orderId\":1,\"correlationId\":\"corr-123\",\"eventId\":\"event-123\",\"status\":\"pending\"}");

        StepVerifier.create(redisTemplate.opsForStream()
                        .add("orders", eventMap)
                        .then())
                .verifyComplete();

        // Verificar que el evento está en el stream
        StepVerifier.create(redisTemplate.opsForStream()
                        .read(Map.class, StreamOffset.fromStart("orders")))
                .expectNextMatches(record -> {
                    Map<String, Object> values = (Map<String, Object>) record.getValue();
                    return values.get("orderId").equals(1L) &&
                            values.get("correlationId").equals("corr-123") &&
                            values.get("type").equals("OrderCreated");
                })
                .verifyComplete();
    }
}