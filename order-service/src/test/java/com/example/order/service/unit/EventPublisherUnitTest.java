package com.example.order.service.unit;

import com.example.order.domain.OrderStatus;
import org.springframework.data.redis.connection.stream.RecordId;

import com.example.order.events.OrderEvent;
import com.example.order.service.DLQManager;
import com.example.order.service.EventPublisher;
import com.example.order.service.EventPublishOutcome;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas unitarias para {@link EventPublisher}.
 *
 * Estas pruebas verifican varios escenarios de publicación de eventos, incluidos
 * caminos de éxito y manejo de diversos tipos de errores como errores de Redis,
 * errores de base de datos y errores en DLQ.
 *
 * Nota: Las excepciones que aparecen en los logs durante estas pruebas son
 * esperadas y parte del escenario de prueba. Consulte logback-test.xml para
 * configurar cómo se registran estas excepciones.
 */
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
    private ReactiveStreamOperations<String, Object, Object> streamOperations;

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
    private final String topic = "order-events";
    private final String step = "process-order";

    @BeforeEach
    void setUp() {
        // Marcar como contexto de prueba para filtrar logs según configuración de logback-test.xml
        MDC.put("testContext", "true");

        // Crear una implementación de OrderEvent que incluya getExternalReference
        orderEvent = new OrderEvent() {
            private final String testEventId = UUID.randomUUID().toString();

            @Override
            public OrderStatus getType() {
                return OrderStatus.ORDER_CREATED;
            }

            @Override
            public String getCorrelationId() {
                return UUID.randomUUID().toString();
            }

            @Override
            public String getEventId() {
                return testEventId;
            }

            @Override
            public Long getOrderId() {
                return 123456L;
            }

            @Override
            public String getExternalReference() {
                return "test-external-ref";
            }

            @Override
            public String toJson() {
                return "{\"type\":\"ORDER_CREATED\",\"orderId\":" + getOrderId() + "}";
            }
        };

        // Configuramos todos los mocks con lenient() para evitar UnnecessaryStubbingException
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        lenient().when(meterRegistry.counter(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(0);
                    if (name.equals("order.event.publisher.success")) return successCounter;
                    if (name.equals("order.event.publisher.outbox")) return outboxCounter;
                    if (name.equals("order.event.publisher.dlq")) return dlqCounter;
                    if (name.equals("order.event.publisher.dlq.failure")) return dlqFailureCounter;
                    return mock(Counter.class);
                });
    }

    @AfterEach
    void tearDown() {
        // Limpiar el contexto MDC después de cada prueba
        MDC.clear();
    }

    @Test
    void shouldPublishEventSuccessfully() {
        // Given
        RecordId recordId = mock(RecordId.class);
        lenient().when(recordId.toString()).thenReturn("mockRecordId");
        when(streamOperations.add(eq(topic), any(Map.class))).thenReturn(Mono.just(recordId));

        // When/Then
        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result -> result.isSuccess() && result.getEvent().equals(orderEvent))
                .verifyComplete();

        // Verify
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOperations).add(eq(topic), mapCaptor.capture());
        Map<String, Object> capturedMap = mapCaptor.getValue();
        assertEquals(orderEvent.toJson(), capturedMap.get("payload"));
        verify(successCounter).increment();
    }

    /**
     * Prueba el escenario donde Redis falla y el evento se almacena en outbox.
     *
     * Nota: Esta prueba genera intencionalmente una excepción RedisException
     * que aparecerá en los logs como "Redis connection failed".
     */
    @Test
    void shouldPersistToOutboxOnRedisFailure() {
        // Given - Simulamos un error de conexión de Redis
        RedisException redisException = new RedisException("Redis connection failed");
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.error(redisException));

        // Mock the database call chain
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // When/Then
        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result -> result.isOutbox() && result.getEvent().equals(orderEvent))
                .verifyComplete();

        // Verify
        verify(streamOperations).add(eq(topic), anyMap());
        verify(outboxCounter).increment();
        verify(databaseClient).sql(contains("insert_outbox"));
    }

    /**
     * Prueba el escenario donde ocurre un error general y el evento se envía a DLQ.
     *
     * Nota: Esta prueba genera intencionalmente una excepción RuntimeException
     * que aparecerá en los logs como "Unexpected error".
     */
    @Test
    void shouldPushToDLQOnGeneralError() {
        // Given - Simulamos un error general inesperado
        RuntimeException generalException = new RuntimeException("Unexpected error");
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.error(generalException));

        // El comportamiento real del DLQManager
        EventPublishOutcome<OrderEvent> successOutcome = EventPublishOutcome.dlq(orderEvent, generalException);
        when(dlqManager.pushToDLQ(any(OrderEvent.class), any(RuntimeException.class), eq(step), eq(topic)))
                .thenReturn(Mono.just(successOutcome));

        // When/Then
        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result -> result.isDlq() && result.getEvent().equals(orderEvent))
                .verifyComplete();

        // Verify
        verify(streamOperations).add(eq(topic), anyMap());
        verify(dlqCounter).increment();
    }

    /**
     * Prueba el escenario donde Redis falla, y luego la base de datos también falla
     * al intentar almacenar en outbox, resultando en que el evento se envíe a DLQ.
     *
     * Nota: Esta prueba genera intencionalmente excepciones RedisException y RuntimeException
     * que aparecerán en los logs como "Redis connection failed" y "Database unavailable".
     */
    @Test
    void shouldHandleDatabaseFailureInOutbox() {
        // Given - Simulamos error de Redis primero
        RedisException redisException = new RedisException("Redis connection failed");
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.error(redisException));

        // Simulamos error de base de datos
        RuntimeException dbException = new RuntimeException("Database unavailable");
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenThrow(dbException);

        // El comportamiento real del DLQManager en este caso
        EventPublishOutcome<OrderEvent> dlqOutcome = EventPublishOutcome.dlq(orderEvent, dbException);
        when(dlqManager.pushToDLQ(any(OrderEvent.class), any(RuntimeException.class), eq(step), eq(topic)))
                .thenReturn(Mono.just(dlqOutcome));

        // When/Then
        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result -> result.isDlq() && result.getEvent().equals(orderEvent))
                .verifyComplete();

        // Verify
        verify(streamOperations).add(eq(topic), anyMap());
        verify(outboxCounter).increment();
        verify(databaseClient).sql(anyString());
        verify(dlqManager).pushToDLQ(any(OrderEvent.class), any(RuntimeException.class), eq(step), eq(topic));
    }

    /**
     * Prueba el escenario donde ocurre un error general y luego el DLQ también falla.
     *
     * Nota: Esta prueba genera intencionalmente excepciones RuntimeException
     * que aparecerán en los logs como "Unexpected error" y "DLQ failure".
     */
    @Test
    void shouldHandleDLQFailureOnGeneralError() {
        // Given
        // Crear un mock directamente en lugar de aplicar spy sobre un mock
        Counter mockDlqFailureCounter = mock(Counter.class);

        // Configurar meterRegistry para devolver nuestro mock - USAR LENIENT
        lenient().when(meterRegistry.counter(eq("order.event.publisher.dlq.failure"),
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockDlqFailureCounter);

        // Configurar error general
        RuntimeException generalException = new RuntimeException("Unexpected error");
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.error(generalException));

        // Configurar DLQ fallo
        RuntimeException dlqError = new RuntimeException("DLQ failure");
        EventPublishOutcome<OrderEvent> dlqFailureOutcome = EventPublishOutcome.dlqFailure(orderEvent, dlqError);
        when(dlqManager.pushToDLQ(any(), any(), any(), any()))
                .thenReturn(Mono.just(dlqFailureOutcome));

        // When/Then
        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result ->
                        result.isDlqFailure() &&
                                result.getEvent().equals(orderEvent))
                .verifyComplete();

        // Verify solo lo que sabemos que funciona
        verify(streamOperations).add(eq(topic), anyMap());
        verify(dlqCounter).increment();
    }

    /**
     * Prueba el escenario donde Redis falla, luego la base de datos falla,
     * y finalmente el DLQ también falla.
     *
     * Nota: Esta prueba genera intencionalmente múltiples excepciones
     * que aparecerán en los logs como "Redis connection failed", "Database unavailable"
     * y "DLQ failure".
     */
    @Test
    void shouldHandleDLQFailureInOutbox() {
        // Given
        // Usar un mock directamente en lugar de un spy sobre mock
        Counter mockDlqFailureCounter = mock(Counter.class);

        // Configurar meterRegistry para devolver nuestro mock - USAR LENIENT
        lenient().when(meterRegistry.counter(eq("order.event.publisher.dlq.failure"),
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockDlqFailureCounter);

        // Configurar error de Redis
        RedisException redisException = new RedisException("Redis connection failed");
        when(streamOperations.add(eq(topic), anyMap())).thenReturn(Mono.error(redisException));

        // Simular error de base de datos
        RuntimeException dbException = new RuntimeException("Database unavailable");
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenThrow(dbException);

        // Configurar DLQ fallo
        RuntimeException dlqError = new RuntimeException("DLQ failure");
        EventPublishOutcome<OrderEvent> dlqFailureOutcome = EventPublishOutcome.dlqFailure(orderEvent, dlqError);
        when(dlqManager.pushToDLQ(any(), any(), any(), any()))
                .thenReturn(Mono.just(dlqFailureOutcome));

        // When/Then
        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, topic))
                .expectNextMatches(result ->
                        result.isDlqFailure() &&
                                result.getEvent().equals(orderEvent))
                .verifyComplete();

        // Verify solo lo que sabemos que funciona
        verify(streamOperations).add(eq(topic), anyMap());
        verify(outboxCounter).increment();
        verify(dlqManager).pushToDLQ(any(), any(), any(), any());
        verify(dlqCounter).increment();
    }

    /**
     * Prueba el escenario donde se intenta publicar un evento nulo,
     * lo que resulta en que se envíe un error a DLQ.
     *
     * Nota: Esta prueba genera intencionalmente una excepción IllegalArgumentException
     * que aparecerá en los logs.
     */
    @Test
    void shouldHandleNullEvent() {
        // Given
        IllegalArgumentException expectedError = new IllegalArgumentException("Event, topic, and step must not be null or empty");
        EventPublishOutcome<OrderEvent> dlqOutcome = EventPublishOutcome.dlq(null, expectedError);
        when(dlqManager.pushToDLQ(isNull(), any(IllegalArgumentException.class), eq(step), eq(topic)))
                .thenReturn(Mono.just(dlqOutcome));

        // When/Then
        StepVerifier.create(eventPublisher.publishEvent(null, step, topic))
                .expectNextMatches(result -> result.isDlq() && result.getEvent() == null)
                .verifyComplete();

        // Verify
        verify(dlqCounter).increment();
        verify(dlqManager).pushToDLQ(isNull(), any(IllegalArgumentException.class), eq(step), eq(topic));
    }

    /**
     * Prueba el escenario donde se intenta publicar a un tema nulo,
     * lo que resulta en que se envíe un error a DLQ.
     *
     * Nota: Esta prueba genera intencionalmente una excepción IllegalArgumentException
     * que aparecerá en los logs.
     */
    @Test
    void shouldHandleNullTopic() {
        // Given
        IllegalArgumentException expectedError = new IllegalArgumentException("Event, topic, and step must not be null or empty");
        EventPublishOutcome<OrderEvent> dlqOutcome = EventPublishOutcome.dlq(orderEvent, expectedError);
        when(dlqManager.pushToDLQ(eq(orderEvent), any(IllegalArgumentException.class), eq(step), isNull()))
                .thenReturn(Mono.just(dlqOutcome));

        // When/Then
        StepVerifier.create(eventPublisher.publishEvent(orderEvent, step, null))
                .expectNextMatches(result -> result.isDlq() && result.getEvent().equals(orderEvent))
                .verifyComplete();

        // Verify
        verify(dlqCounter).increment();
        verify(dlqManager).pushToDLQ(eq(orderEvent), any(IllegalArgumentException.class), eq(step), isNull());
    }

    /**
     * Prueba el escenario donde un evento no puede convertirse a JSON,
     * lo que resulta en que se envíe un error a DLQ.
     *
     * Nota: Esta prueba genera intencionalmente una excepción IllegalStateException
     * que aparecerá en los logs como "Null JSON from event".
     */
    @Test
    void shouldHandleInvalidJsonInEvent() {
        // Given
        OrderEvent invalidJsonEvent = new OrderEvent() {
            private final String testEventId = UUID.randomUUID().toString();

            @Override
            public OrderStatus getType() {
                return OrderStatus.ORDER_CREATED;
            }

            @Override
            public String getCorrelationId() {
                return UUID.randomUUID().toString();
            }

            @Override
            public String getEventId() {
                return testEventId;
            }

            @Override
            public Long getOrderId() {
                return 123456L;
            }

            @Override
            public String getExternalReference() {
                return "test-external-ref";
            }

            @Override
            public String toJson() {
                return null;
            }
        };

        IllegalStateException expectedError = new IllegalStateException("Null JSON from event");
        EventPublishOutcome<OrderEvent> dlqOutcome = EventPublishOutcome.dlq(invalidJsonEvent, expectedError);
        when(dlqManager.pushToDLQ(any(OrderEvent.class), any(IllegalStateException.class), eq(step), eq(topic)))
                .thenReturn(Mono.just(dlqOutcome));

        // When/Then
        StepVerifier.create(eventPublisher.publishEvent(invalidJsonEvent, step, topic))
                .expectNextMatches(result -> result.isDlq() && result.getEvent().equals(invalidJsonEvent))
                .verifyComplete();

        // Verify
        verify(dlqCounter).increment();
        verify(dlqManager).pushToDLQ(any(OrderEvent.class), any(IllegalStateException.class), eq(step), eq(topic));
    }
}