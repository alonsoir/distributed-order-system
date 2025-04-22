package com.example.order.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.data.redis.host=localhost",  // Será sobrescrito por DynamicPropertySource
        "spring.data.redis.port=6379"        // Será sobrescrito por DynamicPropertySource
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
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

    @Autowired
    private ReactiveRedisTemplate<String, Map<String, Object>> mapReactiveRedisTemplate;

    @Autowired
    private ReactiveRedisTemplate<String, String> stringReactiveRedisTemplate;

    @Test
    void testStreamOperationsWithMapTemplate() {
        // Nombre único para el stream para evitar interferencias entre tests
        String streamKey = "test-stream-" + UUID.randomUUID();

        // Usar stringReactiveRedisTemplate para evitar problemas de serialización
        ReactiveStreamOperations<String, String, String> streamOps = stringReactiveRedisTemplate.opsForStream();

        // Crear datos para el stream como Strings para evitar problemas de serialización
        Map<String, String> eventData = new HashMap<>();
        eventData.put("orderId", "100");
        eventData.put("customerId", "200");
        eventData.put("status", "PENDING");
        eventData.put("amount", "59.99");
        eventData.put("items", "5");

        // Mapa anidado como JSON string
        eventData.put("metadata", "{\"source\":\"web\",\"priority\":1}");

        // Publicar al stream usando el template de strings
        StepVerifier.create(
                        streamOps
                                .add(streamKey, eventData)
                                .map(RecordId::getValue)
                )
                .expectNextMatches(recordId -> recordId != null && !recordId.isEmpty())
                .verifyComplete();

        // Leer del stream con el mismo template
        StepVerifier.create(
                        streamOps
                                .read(StreamOffset.fromStart(streamKey))
                )
                .expectNextMatches(record -> {
                    if (!(record instanceof MapRecord)) {
                        return false;
                    }

                    MapRecord<String, ?, ?> mapRecord = (MapRecord<String, ?, ?>) record;
                    Map<String, String> value = (Map<String, String>) mapRecord.getValue();

                    // Verificar los valores principales como strings
                    boolean basicValuesMatch =
                            value.get("orderId").equals("100") &&
                                    value.get("customerId").equals("200") &&
                                    value.get("status").equals("PENDING") &&
                                    value.get("amount").equals("59.99") &&
                                    value.get("items").equals("5");

                    return basicValuesMatch && value.containsKey("metadata");
                })
                .verifyComplete();
    }

    @Test
    void testPublishAndConsumeOrderEvents() {
        // Nombre único para el stream
        String orderStreamKey = "orders-" + UUID.randomUUID();

        // Usar stringReactiveRedisTemplate para evitar problemas de serialización
        ReactiveStreamOperations<String, String, String> streamOps = stringReactiveRedisTemplate.opsForStream();

        // Crear el evento de orden con todos los valores como strings
        Map<String, String> orderEvent = new HashMap<>();
        orderEvent.put("orderId", "ORD-12345");
        orderEvent.put("correlationId", UUID.randomUUID().toString());
        orderEvent.put("eventId", UUID.randomUUID().toString());
        orderEvent.put("type", "OrderCreated");

        // El payload como JSON string
        orderEvent.put("payload", "{\"orderId\":\"ORD-12345\",\"status\":\"pending\",\"totalAmount\":199.95,\"items\":[{\"productId\":\"PROD-001\",\"quantity\":2,\"price\":49.99},{\"productId\":\"PROD-002\",\"quantity\":1,\"price\":99.97}]}");

        // Publicar al stream con el template de strings
        StepVerifier.create(
                streamOps
                        .add(orderStreamKey, orderEvent)
                        .then()
        ).verifyComplete();

        // Leer del stream con el mismo template
        StepVerifier.create(
                        streamOps
                                .read(StreamOffset.fromStart(orderStreamKey))
                )
                .expectNextMatches(record -> {
                    if (!(record instanceof MapRecord)) {
                        return false;
                    }

                    MapRecord<String, ?, ?> mapRecord = (MapRecord<String, ?, ?>) record;
                    Map<String, String> value = (Map<String, String>) mapRecord.getValue();

                    // Verificar los campos principales
                    return value.get("orderId").equals("ORD-12345") &&
                            value.get("type").equals("OrderCreated") &&
                            value.containsKey("payload");
                })
                .verifyComplete();
    }

    @Test
    void testWriteAndReadMap() {
        Map<String, Object> data = Map.of("key", "value", "number", 42);

        StepVerifier.create(
                        mapReactiveRedisTemplate.opsForValue().set("test:map", data)
                                .then(mapReactiveRedisTemplate.opsForValue().get("test:map"))
                )
                .expectNextMatches(retrieved ->
                        retrieved.get("key").equals("value") && retrieved.get("number").equals(42)
                )
                .verifyComplete();
    }

    @Test
    void shouldPublishEventToRedisStream() {
        // Utilizamos un ReactiveStreamOperations con serializadores String-String para simplificar
        // y evitar problemas de serialización con tipos complejos
        ReactiveStreamOperations<String, String, String> streamOps = stringReactiveRedisTemplate.opsForStream();

        // dado
        Map<String, String> eventMap = new HashMap<>();
        eventMap.put("orderId",       "1");
        eventMap.put("correlationId", "corr-123");
        eventMap.put("eventId",       "event-123");
        eventMap.put("type",          "OrderCreated");
        eventMap.put("payload",       "{\"orderId\":1,\"correlationId\":\"corr-123\",\"eventId\":\"event-123\",\"status\":\"pending\"}");

        // cuando
        StepVerifier.create(
                streamOps
                        .add("orders", eventMap)
                        .then()
        ).verifyComplete();

        // entonces
        StepVerifier.create(
                        streamOps
                                .read(StreamOffset.fromStart("orders"))
                )
                .expectNextMatches(record -> {
                    if (record instanceof MapRecord<?, ?, ?> mapRecord) {
                        Map<String, String> payload = (Map<String, String>) mapRecord.getValue();
                        return payload.get("orderId").equals("1") &&
                                payload.get("correlationId").equals("corr-123") &&
                                payload.get("type").equals("OrderCreated");
                    }
                    return false;
                })
                .verifyComplete();
    }
    /*
    @Test
    void shouldPublishEventToRedisStreamDS() {
        // dado
        Map<String, String> eventMap = new HashMap<>();
        eventMap.put("orderId",       "1");
        eventMap.put("correlationId", "corr-123");
        eventMap.put("eventId",       "event-123");
        eventMap.put("type",          "OrderCreated");
        eventMap.put("payload",       "{\"orderId\":1,\"correlationId\":\"corr-123\",\"eventId\":\"event-123\",\"status\":\"pending\"}");

        // cuando
        StepVerifier.create(
                redisTemplate // Cambiado a reactiveRedisTemplate
                        .opsForStream()
                        .add("orders", eventMap)
                        .then()
        ).verifyComplete();

        // entonces
        StepVerifier.create(
                        redisTemplate // Cambiado a reactiveRedisTemplate
                                .opsForStream()
                                .read(StreamOffset.fromStart("orders"))
                )
                .expectNextMatches(record -> {
                    if (record instanceof MapRecord<?, ?, ?> mapRecord) {
                        Map<String, Object> payload = (Map<String, Object>) mapRecord.getValue();
                        return payload.get("orderId").equals("1") &&
                                payload.get("correlationId").equals("corr-123") &&
                                payload.get("type").equals("OrderCreated");
                    }
                    return false;
                })
                .verifyComplete();
    }

     */
}