package com.example.order.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

@SpringBootTest
class DLQManagerTest {
    @MockBean
    private ReactiveRedisTemplate<String, Object> redisTemplate;
    @Autowired
    private DLQManager dlqManager;

    @Test
    void shouldPushToDLQ() {
        // Mock Redis behavior and test DLQ push
    }
}
