package com.example.order.service.unit;

import com.example.order.events.OrderEvent;
import com.example.order.events.OrderEventType;
import com.example.order.service.DLQManager;
import com.example.order.service.EventPublisher;
import com.example.order.service.EventPublishOutcome;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventPublisherUnitTest {

    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private DLQManager dlqManager;

    @Mock
    private ReactiveStreamOperations<String, Object,Object> streamOperations;

    @Mock
    private Counter successCounter;

    @Mock
    private Counter outboxCounter;

    @Mock
    private Counter dlqCounter;

    @Mock
    private Counter dlqFailureCounter;

    @InjectMocks
    private EventPublisher eventPublisher;

    private OrderEvent orderEvent;
    private String topic = "order-events";
    private String step = "process-order";

    @BeforeEach
    void setUp() {
        orderEvent = new OrderEvent() {
            @Override
            public OrderEventType getType() {
                return OrderEventType.ORDER_CREATED;
            }

            @Override
            public String getCorrelationId() {
                return UUID.randomUUID().toString();
            }

            @Override
            public String getEventId() {
                return UUID.randomUUID().toString();
            }

            @Override
            public Long getOrderId() {
                return Long.valueOf(UUID.randomUUID().toString());
            }

            @Override
            public String toJson() {
                return "{\"type\":\"ORDER_CREATED\",\"orderId\":\"" + getOrderId() + "\"}";
            }
        };

        when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        // Mock MeterRegistry counters
        when(meterRegistry.counter(eq("order.event.publisher.success"), any())).thenReturn(successCounter);
        when(meterRegistry.counter(eq("order.event.publisher.outbox"), any())).thenReturn(outboxCounter);
        when(meterRegistry.counter(eq("order.event.publisher.dlq"), any())).thenReturn(dlqCounter);
        when(meterRegistry.counter(eq("order.event.publisher.dlq.failure"), any())).thenReturn(dlqFailureCounter);
        when(successCounter.withTags(anyString(), anyString(), anyString(), anyString())).thenReturn(successCounter);
        when(outboxCounter.withTags(anyString(), anyString(), anyString(), anyString())).thenReturn(outboxCounter);
        when(dlqCounter.withTags(anyString(), anyString(), anyString(), anyString())).thenReturn(dlqCounter);
        when(dlqFailureCounter.withTags(anyString(), anyString(), anyString(), anyString())).thenReturn(dlqFailureCounter);
    }

    @Test
    void shouldPublishEventSuccessfully() {
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.just("recordId"));

        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result -> result.isSuccess() && result.getEvent().equals(orderEvent))
                .verifyComplete();

        verify(streamOperations).add(eq(topic), argThat(map -> map.get("payload").equals(orderEvent.toJson())));
        verify(successCounter).increment();
        verifyNoInteractions(outboxCounter, dlqCounter, dlqFailureCounter, databaseClient, dlqManager);
    }

    @Test
    void shouldPersistToOutboxOnRedisFailure() {
        RedisException redisException = new RedisException("Redis connection failed");
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.error(redisException));

        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        DatabaseClient.GenericExecuteSpec bindSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(bindSpec);
        when(bindSpec.then()).thenReturn(Mono.empty());

        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result -> result.isOutbox() && result.getEvent().equals(orderEvent))
                .verifyComplete();

        verify(streamOperations).add(eq(topic), anyMap());
        verify(outboxCounter).increment();
        verify(databaseClient).sql("CALL insert_outbox(:type, :correlationId, :eventId, :payload, :topic)");
        verify(executeSpec).bind("type", orderEvent.getType());
        verify(executeSpec).bind("correlationId", orderEvent.getCorrelationId());
        verify(executeSpec).bind("eventId", orderEvent.getEventId());
        verify(executeSpec).bind("payload", orderEvent.toJson());
        verify(executeSpec).bind("topic", topic);
        verifyNoInteractions(successCounter, dlqCounter, dlqFailureCounter, dlqManager);
    }

    @Test
    void shouldPushToDLQOnGeneralError() {
        RuntimeException generalException = new RuntimeException("Unexpected error");
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.error(generalException));
        when(dlqManager.pushToDLQ(orderEvent, generalException, step, topic)).thenReturn(Mono.empty());

        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result -> result.isDlq() && result.getEvent().equals(orderEvent) && result.getError() == generalException)
                .verifyComplete();

        verify(streamOperations).add(eq(topic), anyMap());
        verify(dlqCounter).increment();
        verify(dlqManager).pushToDLQ(orderEvent, generalException, step, topic);
        verifyNoInteractions(successCounter, outboxCounter, dlqFailureCounter, databaseClient);
    }

    @Test
    void shouldHandleDLQFailureOnGeneralError() {
        RuntimeException generalException = new RuntimeException("Unexpected error");
        RuntimeException dlqError = new RuntimeException("DLQ failure");
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.error(generalException));
        when(dlqManager.pushToDLQ(orderEvent, generalException, step, topic)).thenReturn(Mono.error(dlqError));

        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result -> result.isDlqFailure() && result.getEvent().equals(orderEvent) && result.getError() == dlqError)
                .verifyComplete();

        verify(streamOperations).add(eq(topic), anyMap());
        verify(dlqCounter).increment();
        verify(dlqFailureCounter).increment();
        verify(dlqManager).pushToDLQ(orderEvent, generalException, step, topic);
        verifyNoInteractions(successCounter, outboxCounter, databaseClient);
    }

    @Test
    void shouldHandleDatabaseFailureInOutbox() {
        RedisException redisException = new RedisException("Redis connection failed");
        RuntimeException dbException = new RuntimeException("Database unavailable");
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.error(redisException));
        when(databaseClient.sql(anyString())).thenReturn(mock(DatabaseClient.GenericExecuteSpec.class));
        when(databaseClient.sql(anyString()).bind(anyString(), any())).thenThrow(dbException);
        when(dlqManager.pushToDLQ(orderEvent, dbException, step, topic)).thenReturn(Mono.empty());

        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result -> result.isDlq() && result.getEvent().equals(orderEvent) && result.getError() == dbException)
                .verifyComplete();

        verify(streamOperations).add(eq(topic), anyMap());
        verify(outboxCounter).increment();
        verify(databaseClient).sql(anyString());
        verify(dlqManager).pushToDLQ(orderEvent, dbException, step, topic);
        verifyNoInteractions(successCounter, dlqCounter, dlqFailureCounter);
    }

    @Test
    void shouldHandleDLQFailureInOutbox() {
        RedisException redisException = new RedisException("Redis connection failed");
        RuntimeException dbException = new RuntimeException("Database unavailable");
        RuntimeException dlqError = new RuntimeException("DLQ failure");
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.error(redisException));
        when(databaseClient.sql(anyString())).thenReturn(mock(DatabaseClient.GenericExecuteSpec.class));
        when(databaseClient.sql(anyString()).bind(anyString(), any())).thenThrow(dbException);
        when(dlqManager.pushToDLQ(orderEvent, dbException, step, topic)).thenReturn(Mono.error(dlqError));

        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result -> result.isDlqFailure() && result.getEvent().equals(orderEvent) && result.getError() == dlqError)
                .verifyComplete();

        verify(streamOperations).add(eq(topic), anyMap());
        verify(outboxCounter).increment();
        verify(databaseClient).sql(anyString());
        verify(dlqManager).pushToDLQ(orderEvent, dbException, step, topic);
        verify(dlqFailureCounter).increment();
        verifyNoInteractions(successCounter, dlqCounter);
    }

    @Test
    void shouldHandleNullEvent() {
        IllegalArgumentException error = new IllegalArgumentException("Event, topic, and step must not be null or empty");
        when(dlqManager.pushToDLQ(null, error, step, topic)).thenReturn(Mono.empty());

        StepVerifier.create(eventPublisher.publishEvent(null, step, topic))
                .expectNextMatches(result -> result.isDlq() && result.getEvent() == null && result.getError() == error)
                .verifyComplete();

        verify(dlqCounter).increment();
        verify(dlqManager).pushToDLQ(null, error, step, topic);
        verifyNoInteractions(successCounter, outboxCounter, dlqFailureCounter, streamOperations, databaseClient);
    }

    @Test
    void shouldHandleNullTopic() {
        IllegalArgumentException error = new IllegalArgumentException("Event, topic, and step must not be null or empty");
        when(dlqManager.pushToDLQ(orderEvent, error, step, null)).thenReturn(Mono.empty());

        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, null))
                .expectNextMatches(result -> result.isDlq() && result.getEvent() == orderEvent && result.getError() == error)
                .verifyComplete();

        verify(dlqCounter).increment();
        verify(dlqManager).pushToDLQ(orderEvent, error, step, null);
        verifyNoInteractions(successCounter, outboxCounter, dlqFailureCounter, streamOperations, databaseClient);
    }

    @Test
    void shouldHandleInvalidJsonInEvent() {
        OrderEvent invalidJsonEvent = new OrderEvent() {
            @Override
            public OrderEventType getType() {
                return OrderEventType.ORDER_CREATED;
            }

            @Override
            public String getCorrelationId() {
                return UUID.randomUUID().toString();
            }

            @Override
            public String getEventId() {
                return UUID.randomUUID().toString();
            }

            @Override
            public Long getOrderId() {
                return Long.valueOf(UUID.randomUUID().toString());
            }

            @Override
            public String toJson() {
                return null;
            }
        };

        IllegalStateException error = new IllegalStateException("Null JSON from event");
        when(dlqManager.pushToDLQ(invalidJsonEvent, error, step, topic)).thenReturn(Mono.empty());

        StepVerifier.create(eventPublisher.publishEvent(invalidJsonEvent, step, topic))
                .expectNextMatches(result -> result.isDlq() && result.getEvent() == invalidJsonEvent && result.getError() == error)
                .verifyComplete();

        verify(dlqCounter).increment();
        verify(dlqManager).pushToDLQ(invalidJsonEvent, error, step, topic);
        verifyNoInteractions(successCounter, outboxCounter, dlqFailureCounter, streamOperations, databaseClient);
    }
}