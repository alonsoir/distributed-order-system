package com.example.order.service.unit;

import com.example.order.events.OrderEvent;
import com.example.order.events.OrderEventType;
import com.example.order.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DLQManagerUnitTest {

    private static final String TEST_EVENT_ID = "test-event-id";
    private static final String TEST_CORRELATION_ID = "test-correlation-id";
    private static final Long TEST_ORDER_ID = 123456L;
    private static final String TEST_STEP_NAME = "testStep";
    private static final String TEST_TOPIC = "test-topic";
    private static final String DLQ_KEY = "failed-outbox-events";
    private static final String DLQ_SUCCESS_COUNTER = "dead_letter_queue_success";
    private static final String DLQ_FAILURE_COUNTER = "dead_letter_queue_failure";

    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Mock
    private ReactiveListOperations<String, Object> listOperations;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private RedisStatusChecker redisStatusChecker;

    @Mock
    private EventLogger eventLogger;

    @Mock
    private Counter successCounter;

    @Mock
    private Counter failureCounter;

    @TempDir
    Path tempDir;

    private TestDLQManager dlqManager; // Usamos nuestra clase de prueba
    private AbstractDLQManager.DLQConfig dlqConfig;

    @BeforeEach
    void setUp() throws IOException {
        // Configure mocks
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.leftPush(anyString(), any())).thenReturn(Mono.just(1L));
        when(listOperations.rightPop(anyString())).thenReturn(Mono.empty());

        // Configure MeterRegistry mock
        when(meterRegistry.counter(eq(DLQ_SUCCESS_COUNTER), any(String[].class))).thenReturn(successCounter);
        when(meterRegistry.counter(eq(DLQ_FAILURE_COUNTER), any(String[].class))).thenReturn(failureCounter);

        // Configure Redis status checker
        when(redisStatusChecker.isRedisAvailable()).thenReturn(Mono.just(true));

        // Configure EventLogger
        when(eventLogger.logEvent(any(), anyString(), any())).thenReturn(Mono.empty());

        // Configure ObjectMapper - ahora correctamente
        when(objectMapper.readValue(any(File.class), eq(Map.class))).thenReturn(new HashMap<>());

        // Configure DLQ config
        dlqConfig = new AbstractDLQManager.DLQConfig();
        dlqConfig.setRetryMaxAttempts(3);
        dlqConfig.setBackoffSeconds(1);
        dlqConfig.setProcessInterval(1000);
        dlqConfig.setEventExpirationHours(24);

        // Create TestDLQManager (nuestra clase especial para pruebas)
        dlqManager = new TestDLQManager(
                redisTemplate,
                objectMapper,
                meterRegistry,
                redisStatusChecker,
                eventLogger,
                dlqConfig,
                tempDir.toString()
        );
    }

    @Test
    void shouldPushToDLQSuccessfully() {
        // Arrange
        OrderEvent mockEvent = createMockOrderEvent();
        Throwable mockError = new RuntimeException("Test error");

        // Act
        Mono<EventPublishOutcome<OrderEvent>> result = dlqManager.pushToDLQ(
                mockEvent, mockError, TEST_STEP_NAME, TEST_TOPIC);

        // Assert
        StepVerifier.create(result)
                .assertNext(outcome -> {
                    assertThat(outcome.isDlq()).isTrue();
                    assertThat(outcome.getEvent()).isEqualTo(mockEvent);
                    assertThat(outcome.getError()).isEqualTo(mockError);
                })
                .verifyComplete();

        // Verify interactions
        verify(listOperations).leftPush(eq(DLQ_KEY), any(Map.class));
        verify(successCounter).increment();
        verify(failureCounter, never()).increment();
    }

    @Test
    void shouldProcessDLQWhenRedisIsAvailable() throws Exception {
        // Arrange
        Map<String, Object> mockEvent = createMockDLQEntry();
        when(listOperations.rightPop(eq(DLQ_KEY)))
                .thenReturn(Mono.just(mockEvent))
                .thenReturn(Mono.empty());

        // Ahora podemos espiar nuestro TestDLQManager directamente
        TestDLQManager spyDlqManager = Mockito.spy(dlqManager);

        // Y configurar el comportamiento del método
        OrderEvent mockOrderEvent = createMockOrderEvent();
        doReturn(mockOrderEvent).when(spyDlqManager).reconstructEvent(any());

        // Act
        spyDlqManager.processDLQ();

        // A small delay to allow async processing
        sleep(100);

        // Verify
        verify(redisStatusChecker).isRedisAvailable();
        verify(listOperations).rightPop(eq(DLQ_KEY));
        verify(eventLogger).logEvent(any(OrderEvent.class), eq(TEST_TOPIC), any(Throwable.class));
    }


    @Test
    void shouldHandleNullEventGracefully() {
        // Act
        Mono<EventPublishOutcome<OrderEvent>> result = dlqManager.pushToDLQ(
                null, new RuntimeException("Test error"), TEST_STEP_NAME, TEST_TOPIC);

        // Assert
        StepVerifier.create(result)
                .assertNext(outcome -> {
                    assertThat(outcome.isDlqFailure()).isTrue();
                    assertThat(outcome.getEvent()).isNull();
                    assertThat(outcome.getError()).isInstanceOf(IllegalArgumentException.class);
                })
                .verifyComplete();

        // Verify no interactions with Redis
        verify(listOperations, never()).leftPush(anyString(), any());
    }

    @Test
    void shouldHandleRedisFailureAndUseBackupLogger() {
        // Arrange
        OrderEvent mockEvent = createMockOrderEvent();
        Throwable mockError = new RuntimeException("Test error");
        RedisException redisException = new RedisException("Redis connection failed");

        // Configure Redis to fail
        when(listOperations.leftPush(anyString(), any())).thenReturn(Mono.error(redisException));

        // Act
        Mono<EventPublishOutcome<OrderEvent>> result = dlqManager.pushToDLQ(
                mockEvent, mockError, TEST_STEP_NAME, TEST_TOPIC);

        // Assert
        StepVerifier.create(result)
                .assertNext(outcome -> {
                    assertThat(outcome.isDlqFailure()).isTrue();
                    assertThat(outcome.getEvent()).isEqualTo(mockEvent);
                    assertThat(outcome.getError()).isInstanceOf(Throwable.class);
                })
                .verifyComplete();

        // Verify fallback to event logger
        verify(eventLogger).logEvent(eq(mockEvent), eq(TEST_TOPIC), eq(mockError));
        verify(failureCounter).increment();
    }

    @Test
    void shouldCaptureEventMetadataCorrectly() {
        // Arrange
        OrderEvent mockEvent = createMockOrderEvent();
        Throwable mockError = new RuntimeException("Test error");

        // Capture the map being pushed to Redis
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);

        // Act
        Mono<EventPublishOutcome<OrderEvent>> result = dlqManager.pushToDLQ(
                mockEvent, mockError, TEST_STEP_NAME, TEST_TOPIC);

        // Assert
        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();

        verify(listOperations).leftPush(eq(DLQ_KEY), mapCaptor.capture());

        Map<String, Object> capturedMap = mapCaptor.getValue();
        assertThat(capturedMap).containsEntry("eventId", TEST_EVENT_ID);
        assertThat(capturedMap).containsEntry("correlationId", TEST_CORRELATION_ID);
        assertThat(capturedMap).containsEntry("orderId", TEST_ORDER_ID);
        assertThat(capturedMap).containsEntry("topic", TEST_TOPIC);
        assertThat(capturedMap).containsEntry("error", "Test error");
        assertThat(capturedMap).containsKey("timestamp");
        assertThat(capturedMap).containsEntry("retries", 0);
    }

    @Test
    void shouldHandleRetryLogicForRedisExceptions() {
        // Arrange
        OrderEvent mockEvent = createMockOrderEvent();
        Throwable mockError = new RuntimeException("Test error");
        RedisException redisException = new RedisException("Redis connection failed");

        // Configure Redis to fail
        when(listOperations.leftPush(anyString(), any())).thenReturn(Mono.error(redisException));

        // Act
        Mono<EventPublishOutcome<OrderEvent>> result = dlqManager.pushToDLQ(
                mockEvent, mockError, TEST_STEP_NAME, TEST_TOPIC);

        // Assert
        StepVerifier.create(result)
                .assertNext(outcome -> {
                    assertThat(outcome.isDlqFailure()).isTrue();
                })
                .verifyComplete();

        // Verify Redis was called and eventLogger was called as a fallback
        verify(listOperations).leftPush(eq(DLQ_KEY), any(Map.class));
        verify(eventLogger).logEvent(any(), anyString(), any());
    }



    @Test
    void shouldProcessLogBasedDLQWhenRedisIsUnavailable() throws Exception {
        // Arrange
        when(redisStatusChecker.isRedisAvailable()).thenReturn(Mono.just(false));

        // Create a mock log file to be processed
        Path mockLogFile = Files.createFile(tempDir.resolve("test-event.json"));
        Map<String, Object> logEntry = createMockDLQEntry();

        // Mock the ObjectMapper to return our test data
        when(objectMapper.readValue(mockLogFile.toFile(), Map.class)).thenReturn(logEntry);

        // Mock la reconstrucción del evento usando la clase de prueba TestDLQManager
        OrderEvent mockOrderEvent = createMockOrderEvent();
        TestDLQManager spyDlqManager = Mockito.spy((TestDLQManager) dlqManager);
        doReturn(mockOrderEvent).when(spyDlqManager).reconstructEvent(any());

        // Act
        spyDlqManager.processDLQ();

        // A small delay to allow async processing
        sleep(200);

        // Modificamos la verificación para aceptar exactamente 2 llamadas
        verify(redisStatusChecker, times(2)).isRedisAvailable();

        // En su lugar, verificamos que se procesó el archivo de log
        verify(objectMapper).readValue(mockLogFile.toFile(), Map.class);
    }

    private OrderEvent createMockOrderEvent() {
        OrderEvent mockEvent = mock(OrderEvent.class);
        when(mockEvent.getEventId()).thenReturn(TEST_EVENT_ID);
        when(mockEvent.getCorrelationId()).thenReturn(TEST_CORRELATION_ID);
        when(mockEvent.getOrderId()).thenReturn(TEST_ORDER_ID);
        when(mockEvent.getType()).thenReturn(OrderEventType.ORDER_CREATED);
        when(mockEvent.toJson()).thenReturn("{\"eventId\":\"" + TEST_EVENT_ID + "\"}");
        return mockEvent;
    }

    private Map<String, Object> createMockDLQEntry() {
        Map<String, Object> entry = new HashMap<>();
        entry.put("eventId", TEST_EVENT_ID);
        entry.put("correlationId", TEST_CORRELATION_ID);
        entry.put("orderId", TEST_ORDER_ID);
        entry.put("type", "ORDER_CREATED");
        entry.put("topic", TEST_TOPIC);
        entry.put("payload", "{\"eventId\":\"" + TEST_EVENT_ID + "\",\"type\":\"ORDER_CREATED\"}");
        entry.put("error", "Test error");
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("retries", 1);
        return entry;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}