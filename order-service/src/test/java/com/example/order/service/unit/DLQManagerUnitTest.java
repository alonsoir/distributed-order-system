package com.example.order.service.unit;

import com.example.order.service.DLQManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("unit")
class DLQManagerUnitTest {
    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;
    @Autowired
    private DLQManager dlqManager;

    @Test
    void shouldPushToDLQ() {
        // Mock Redis behavior and test DLQ push
    }
}
