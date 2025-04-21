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

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.data.redis.host=localhost",  // Será sobrescrito por DynamicPropertySource
        "spring.data.redis.port=6379"        // Será sobrescrito por DynamicPropertySource
})
@Testcontainers
class RedisUnitTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    ReactiveRedisTemplate<String, Object> redisTemplate;

    @Test
    void shouldPublishEventToRedisStream() {
        // dado
        Map<String, Object> eventMap = new HashMap<>();
        eventMap.put("orderId",       1L);
        eventMap.put("correlationId", "corr-123");
        eventMap.put("eventId",       "event-123");
        eventMap.put("type",          "OrderCreated");
        eventMap.put("payload",       "{\"orderId\":1,\"correlationId\":\"corr-123\",\"eventId\":\"event-123\",\"status\":\"pending\"}");

        // cuando
        StepVerifier.create(redisTemplate
                        .opsForStream()
                        .add("orders", eventMap)
                        .then())
                .verifyComplete();

        // entonces
        StepVerifier.create(redisTemplate
                        .opsForStream()
                        .read(Object.class, StreamOffset.fromStart("orders")))
                .expectNextMatches(record -> {
                    // el value puede venir como Map<String,Object> o como LinkedHashMap
                    @SuppressWarnings("unchecked")
                    Map<String,Object> payload = (Map<String,Object>)record.getValue();
                    return  payload.get("orderId").equals(1L) &&
                            payload.get("correlationId").equals("corr-123") &&
                            payload.get("type").equals("OrderCreated");
                })
                .verifyComplete();
    }
}