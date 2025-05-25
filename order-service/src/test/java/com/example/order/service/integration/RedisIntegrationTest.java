package com.example.order.service.integration;

import com.example.order.events.EventTopics;
import com.example.order.domain.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
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

@ActiveProfiles("integration")
@SpringBootTest(
        // Ahora puedes incluir la aplicación principal sin conflictos
        classes = {
                com.example.order.OrderServiceApplication.class,
                RedisIntegrationTest.PubOnlyRedisConfig.class
        },
        properties = {
                "spring.cloud.config.enabled=false",
                "spring.cloud.config.import-check.enabled=false",
                // Deshabilitar la configuración deprecated para evitar conflictos
                "spring.main.allow-bean-definition-overriding=false"
        }
)
@ImportAutoConfiguration({
        RedisAutoConfiguration.class,
        RedisReactiveAutoConfiguration.class
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RedisIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void overrideRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @TestConfiguration
    static class PubOnlyRedisConfig {

        @Bean
        public ReactiveRedisTemplate<String, String> stringReactiveRedisTemplate(
                ReactiveRedisConnectionFactory connectionFactory) {

            RedisSerializationContext<String, String> serializationContext =
                    RedisSerializationContext.<String, String>newSerializationContext()
                            .key(StringRedisSerializer.UTF_8)
                            .value(StringRedisSerializer.UTF_8)
                            .hashKey(StringRedisSerializer.UTF_8)
                            .hashValue(StringRedisSerializer.UTF_8)
                            .build();

            return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
        }

        @Bean
        public ReactiveRedisTemplate<String, Map<String, Object>> mapReactiveRedisTemplate(
                ReactiveRedisConnectionFactory connectionFactory,
                ObjectMapper redisObjectMapper) {

            Jackson2JsonRedisSerializer<Map<String, Object>> serializer =
                    new Jackson2JsonRedisSerializer<>(
                            redisObjectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
                    );

            RedisSerializationContext<String, Map<String, Object>> context = RedisSerializationContext
                    .<String, Map<String, Object>>newSerializationContext(new StringRedisSerializer())
                    .value(RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                    .hashKey(new StringRedisSerializer())
                    .hashValue(RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                    .build();

            return new ReactiveRedisTemplate<>(connectionFactory, context);
        }
    }

    @Autowired
    ReactiveRedisTemplate<String, String> stringReactiveRedisTemplate;

    @Autowired
    ReactiveRedisTemplate<String, Map<String, Object>> mapReactiveRedisTemplate;

    // ===== TESTS DE PUBLICACIÓN A TODOS LOS TOPICS PUB =====

    @Test
    void testPublishToOrderCreatedTopic() {
        String topic = EventTopics.getTopicName(OrderStatus.ORDER_CREATED);
        publishAndVerifyEvent(topic, createOrderEvent("ORDER_CREATED"));
    }

    @Test
    void testPublishToOrderProcessingTopic() {
        String topic = EventTopics.getTopicName(OrderStatus.ORDER_PROCESSING);
        publishAndVerifyEvent(topic, createOrderEvent("ORDER_PROCESSING"));
    }

    @Test
    void testPublishToOrderCompletedTopic() {
        String topic = EventTopics.getTopicName(OrderStatus.ORDER_COMPLETED);
        publishAndVerifyEvent(topic, createOrderEvent("ORDER_COMPLETED"));
    }

    @Test
    void testPublishToOrderFailedTopic() {
        String topic = EventTopics.getTopicName(OrderStatus.ORDER_FAILED);
        publishAndVerifyEvent(topic, createOrderEvent("ORDER_FAILED"));
    }

    @Test
    void testPublishToStockReservedTopic() {
        String topic = EventTopics.getTopicName(OrderStatus.STOCK_RESERVED);
        publishAndVerifyEvent(topic, createStockEvent("STOCK_RESERVED"));
    }

    @Test
    void testPublishToPaymentConfirmedTopic() {
        String topic = EventTopics.getTopicName(OrderStatus.PAYMENT_CONFIRMED);
        publishAndVerifyEvent(topic, createPaymentEvent("PAYMENT_CONFIRMED"));
    }

    @Test
    void testPublishToAllPubTopics() {
        String[] pubTopics = {
                "order-created",
                "order-processing",
                "order-completed",
                "order-failed",
                "stock-reserved",
                "payment-confirmed",
                "shipping-pending"
        };

        for (String topic : pubTopics) {
            Map<String, String> event = createGenericPubEvent(topic);
            publishAndVerifyEvent(topic, event);
        }
    }

    @Test
    void testHighVolumePublishing() {
        String topic = "load-test-" + UUID.randomUUID();
        ReactiveStreamOperations<String, String, String> streamOps =
                stringReactiveRedisTemplate.opsForStream();

        for (int i = 0; i < 100; i++) {
            Map<String, String> event = createGenericPubEvent("LOAD_TEST_" + i);

            StepVerifier.create(streamOps.add(topic, event))
                    .expectNextCount(1)
                    .verifyComplete();
        }

        StepVerifier.create(streamOps.size(topic))
                .expectNext(100L)
                .verifyComplete();
    }

    @Test
    void testEventStructureCompliance() {
        String topic = "structure-test-" + UUID.randomUUID();
        Map<String, String> event = createCompliantOrderEvent();

        publishAndVerifyEvent(topic, event);

        ReactiveStreamOperations<String, String, String> streamOps =
                stringReactiveRedisTemplate.opsForStream();

        StepVerifier.create(streamOps.read(StreamOffset.fromStart(topic)))
                .expectNextMatches(record -> {
                    Map<String, String> value = (Map<String, String>) ((MapRecord<?,?,?>)record).getValue();
                    return value.containsKey("eventId") &&
                            value.containsKey("correlationId") &&
                            value.containsKey("orderId") &&
                            value.containsKey("type") &&
                            value.containsKey("timestamp") &&
                            value.containsKey("source") &&
                            value.get("source").equals("order-service-pub");
                })
                .verifyComplete();
    }

    @Test
    void testMapTemplateWithComplexOrderData() {
        String key = "complex-order:" + UUID.randomUUID();
        Map<String, Object> orderData = Map.of(
                "orderId", "ORD-12345",
                "amount", 199.99,
                "currency", "EUR",
                "items", Map.of(
                        "item1", Map.of("quantity", 2, "price", 99.99),
                        "item2", Map.of("quantity", 1, "price", 99.99)
                ),
                "customer", Map.of(
                        "id", "CUST-567",
                        "email", "test@example.com"
                ),
                "metadata", Map.of(
                        "source", "pub-integration-test",
                        "timestamp", System.currentTimeMillis(),
                        "version", "v2"
                )
        );

        StepVerifier.create(
                        mapReactiveRedisTemplate.opsForValue().set(key, orderData)
                                .then(mapReactiveRedisTemplate.opsForValue().get(key))
                )
                .expectNextMatches(retrieved ->
                        retrieved.get("orderId").equals("ORD-12345") &&
                                retrieved.get("amount").equals(199.99) &&
                                retrieved.containsKey("items") &&
                                retrieved.containsKey("customer") &&
                                ((Map<String, Object>) retrieved.get("metadata")).get("source").equals("pub-integration-test")
                )
                .verifyComplete();
    }

    // ===== HELPER METHODS =====

    private void publishAndVerifyEvent(String topic, Map<String, String> event) {
        ReactiveStreamOperations<String, String, String> streamOps =
                stringReactiveRedisTemplate.opsForStream();

        StepVerifier.create(streamOps.add(topic, event))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(streamOps.read(StreamOffset.fromStart(topic)))
                .expectNextMatches(record -> {
                    Map<String, String> value = (Map<String, String>) ((MapRecord<?,?,?>)record).getValue();
                    return value.get("eventId").equals(event.get("eventId")) &&
                            value.get("type").equals(event.get("type"));
                })
                .verifyComplete();
    }

    private Map<String, String> createOrderEvent(String eventType) {
        Map<String, String> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("orderId", "ORD-" + UUID.randomUUID().toString().substring(0, 8));
        event.put("type", eventType);
        event.put("timestamp", String.valueOf(System.currentTimeMillis()));
        event.put("source", "order-service-pub");
        event.put("payload", "{\"amount\":99.99,\"quantity\":2,\"currency\":\"EUR\"}");
        return event;
    }

    private Map<String, String> createStockEvent(String eventType) {
        Map<String, String> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("orderId", "ORD-" + UUID.randomUUID().toString().substring(0, 8));
        event.put("productId", "PROD-" + UUID.randomUUID().toString().substring(0, 8));
        event.put("type", eventType);
        event.put("timestamp", String.valueOf(System.currentTimeMillis()));
        event.put("source", "inventory-service-pub");
        event.put("quantity", "5");
        event.put("payload", "{\"productId\":\"PROD-123\",\"reservedQuantity\":5,\"warehouseId\":\"WH-001\"}");
        return event;
    }

    private Map<String, String> createPaymentEvent(String eventType) {
        Map<String, String> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("orderId", "ORD-" + UUID.randomUUID().toString().substring(0, 8));
        event.put("type", eventType);
        event.put("timestamp", String.valueOf(System.currentTimeMillis()));
        event.put("source", "payment-service-pub");
        event.put("amount", "199.99");
        event.put("currency", "EUR");
        event.put("payload", "{\"amount\":199.99,\"currency\":\"EUR\",\"paymentMethod\":\"CARD\"}");
        return event;
    }

    private Map<String, String> createGenericPubEvent(String topic) {
        Map<String, String> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("type", topic.toUpperCase().replace("-", "_"));
        event.put("timestamp", String.valueOf(System.currentTimeMillis()));
        event.put("source", "pub-integration-test");
        event.put("payload", "{\"test\":true,\"topic\":\"" + topic + "\"}");
        return event;
    }

    private Map<String, String> createCompliantOrderEvent() {
        Map<String, String> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("orderId", "ORD-" + UUID.randomUUID().toString().substring(0, 8));
        event.put("type", "ORDER_CREATED");
        event.put("timestamp", String.valueOf(System.currentTimeMillis()));
        event.put("source", "order-service-pub");
        event.put("version", "v1");
        event.put("payload", "{\"amount\":99.99,\"currency\":\"EUR\",\"customerId\":\"CUST-123\"}");
        return event;
    }
}