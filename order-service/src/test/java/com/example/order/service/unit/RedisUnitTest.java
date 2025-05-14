package com.example.order.service.unit;

import com.example.order.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.ReactiveStreamOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ActiveProfiles("test")
@SpringBootTest(
        classes = RedisConfig.class,
        properties = {
                "spring.cloud.config.enabled=false",
                "spring.cloud.config.import-check.enabled=false"
        }
)
@ImportAutoConfiguration({
        RedisAutoConfiguration.class,
        RedisReactiveAutoConfiguration.class
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RedisUnitTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void overrideRedisProperties(DynamicPropertyRegistry registry) {
        // inject the container host & mapped port into spring.redis.*
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    // inyecta tu template definido en RedisConfig
    @Autowired
    ReactiveRedisTemplate<String, String> stringReactiveRedisTemplate;

    @Autowired
    ReactiveRedisTemplate<String, Map<String, Object>> mapReactiveRedisTemplate;

    @Test
    void testPublishAndConsumeOrderEvents() {
        String key = "orders-" + UUID.randomUUID();
        ReactiveStreamOperations<String, String, String> streamOps =
                stringReactiveRedisTemplate.opsForStream();

        Map<String, String> event = new HashMap<>();
        event.put("orderId", "ORD-12345");
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("eventId", UUID.randomUUID().toString());
        event.put("type", "OrderCreated");
        event.put("payload", "{\"foo\":\"bar\"}");

        // publica
        StepVerifier.create(streamOps.add(key, event).then())
                .verifyComplete();

        // consume
        StepVerifier.create(streamOps.read(StreamOffset.fromStart(key)))
                .expectNextMatches(rec -> {
                    Map<String, String> v = (Map<String, String>) ((MapRecord<?,?,?>)rec).getValue();
                    return v.get("orderId").equals("ORD-12345")
                            && v.get("type").equals("OrderCreated");
                })
                .verifyComplete();
    }

    @Test
    void testStreamOperationsWithMapTemplate() {
        String stream = "test-" + UUID.randomUUID();
        ReactiveStreamOperations<String, String, String> ops = stringReactiveRedisTemplate.opsForStream();
        Map<String,String> data = Map.of("a","1","b","2");

        StepVerifier.create(ops.add(stream, data).map(id->id.getValue()))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(ops.read(StreamOffset.fromStart(stream)))
                .expectNextMatches(r -> ((MapRecord<?,?,?>)r).getValue().get("a").equals("1"))
                .verifyComplete();
    }

    @Test
    void testWriteAndReadMap() {
        String k = "map:" + UUID.randomUUID();
        Map<String,Object> payload = Map.of("x","y","n",3);

        StepVerifier.create(mapReactiveRedisTemplate.opsForValue().set(k,payload)
                        .then(mapReactiveRedisTemplate.opsForValue().get(k)))
                .expectNextMatches(m -> m.get("n").equals(3) && m.get("x").equals("y"))
                .verifyComplete();
    }
}
