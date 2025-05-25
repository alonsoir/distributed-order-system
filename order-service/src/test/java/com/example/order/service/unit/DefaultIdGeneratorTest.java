package com.example.order.service.unit;

import com.example.order.service.DefaultIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Configurando Mockito en modo LENIENT para permitir stubs no utilizados
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@ActiveProfiles("unit")

class DefaultIdGeneratorTest {

    @Mock
    private ReactiveRedisTemplate<String, Long> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, Long> valueOperations;

    private DefaultIdGenerator idGenerator;

    private static final int TEST_NODE_ID = 42;
    private static final String ORDER_ID_KEY = "order:id:counter";

    @BeforeEach
    void setUp() {
        // Configuraciones necesarias para los tests que usan Redis
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // No configuramos hasKey globalmente, lo haremos en cada test que lo necesite

        // Crear el generador con configuración de test
        idGenerator = new DefaultIdGenerator(redisTemplate, TEST_NODE_ID);

        // Activar el monitoreo y la verificación de colisiones para pruebas
        ReflectionTestUtils.setField(idGenerator, "monitoringEnabled", true);
        ReflectionTestUtils.setField(idGenerator, "checkCollisions", true);
        ReflectionTestUtils.setField(idGenerator, "redisFallbackEnabled", true);

        // Configurar un registro de IDs para testing
        ConcurrentHashMap<String, Boolean> testRegistry = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(idGenerator, "idRegistry", testRegistry);
    }

    @Test
    void generateCorrelationId_ShouldReturnValidUUID() {
        // Given
        final int expectedLength = 36; // UUID standard length

        // When
        String correlationId = idGenerator.generateCorrelationId();

        // Then
        assertNotNull(correlationId);
        assertEquals(expectedLength, correlationId.length());
        // Verify it's a valid UUID
        assertDoesNotThrow(() -> java.util.UUID.fromString(correlationId));
    }

    @Test
    void generateExternalReference_ShouldReturnValidUUID() {
        // Given
        final int expectedLength = 36; // UUID standard length

        // When
        String externalRef = idGenerator.generateExternalReference();

        // Then
        assertNotNull(externalRef);
        assertEquals(expectedLength, externalRef.length());
        // Verify it's a valid UUID
        assertDoesNotThrow(() -> java.util.UUID.fromString(externalRef));
    }

    @Test
    void generateEventId_ShouldReturnValidUUID() {
        // Given
        final int expectedLength = 36; // UUID standard length

        // When
        String eventId = idGenerator.generateEventId();

        // Then
        assertNotNull(eventId);
        assertEquals(expectedLength, eventId.length());
        // Verify it's a valid UUID
        assertDoesNotThrow(() -> java.util.UUID.fromString(eventId));
    }

    @Test
    void generateEventId_ShouldDetectCollisions() {
        // Given
        ConcurrentHashMap<String, Boolean> testRegistry = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(idGenerator, "idRegistry", testRegistry);

        // Create a UUID to simulate a collision
        String existingId = java.util.UUID.randomUUID().toString();
        testRegistry.put(existingId, Boolean.TRUE);

        // No necesitamos crear un spy para este test, simplemente verificamos
        // que los IDs generados no colisionan con los existentes
        assertTrue(testRegistry.containsKey(existingId));
        String newId = idGenerator.generateEventId();
        assertNotEquals(existingId, newId);

        // Verificar que el nuevo ID fue añadido al registro
        assertTrue(testRegistry.containsKey(newId));
    }

    @Test
    void generateOrderId_ShouldReturnUniqueIds() {
        // Given
        final int count = 1000;
        Set<Long> generatedIds = new HashSet<>();

        // When
        for (int i = 0; i < count; i++) {
            Long orderId = idGenerator.generateOrderId();
            generatedIds.add(orderId);
        }

        // Then
        assertEquals(count, generatedIds.size(), "All generated IDs should be unique");
    }

    @Test
    void generateOrderId_ShouldIncludeNodeId() {
        // Given
        final int nodeId = TEST_NODE_ID;

        // When
        Long orderId = idGenerator.generateOrderId();
        int extractedNodeId = idGenerator.extractNodeIdFromOrderId(orderId);

        // Then
        assertEquals(nodeId, extractedNodeId, "Generated ID should contain the configured node ID");
    }

    @Test
    void generateOrderId_ShouldHaveRecentTimestamp() {
        // Given
        final long now = Instant.now().toEpochMilli();
        final long fiveSecondsAgo = now - 5000;

        // When
        Long orderId = idGenerator.generateOrderId();
        long timestamp = idGenerator.extractTimestampFromOrderId(orderId);

        // Then
        assertTrue(timestamp >= fiveSecondsAgo, "Timestamp should be recent");
        assertTrue(timestamp <= now + 1000, "Timestamp should not be in the future (with 1s tolerance)");
    }

    @Test
    void generateOrderIdWithRedis_ShouldReturnRedisValue() {
        // Given
        final Long expectedId = 12345L;
        when(valueOperations.increment(ORDER_ID_KEY)).thenReturn(Mono.just(expectedId));

        // When
        Mono<Long> orderIdMono = idGenerator.generateOrderIdWithRedis();

        // Then
        StepVerifier.create(orderIdMono)
                .expectNext(expectedId)
                .verifyComplete();

        verify(valueOperations).increment(ORDER_ID_KEY);
    }

    @Test
    void generateOrderIdWithRedis_ShouldFallbackOnError() {
        // Given
        when(valueOperations.increment(ORDER_ID_KEY)).thenReturn(Mono.error(new RuntimeException("Redis error")));

        // When
        Mono<Long> orderIdMono = idGenerator.generateOrderIdWithRedis();

        // Then
        StepVerifier.create(orderIdMono)
                .expectNextCount(1) // Debería generar un ID de fallback
                .verifyComplete();

        verify(valueOperations).increment(ORDER_ID_KEY);
    }

    @Test
    void generateOrderIdWithRedis_ShouldPropagateErrorWhenFallbackDisabled() {
        // Given
        ReflectionTestUtils.setField(idGenerator, "redisFallbackEnabled", false);
        when(valueOperations.increment(ORDER_ID_KEY)).thenReturn(Mono.error(new RuntimeException("Redis error")));

        // When
        Mono<Long> orderIdMono = idGenerator.generateOrderIdWithRedis();

        // Then
        StepVerifier.create(orderIdMono)
                .expectError(RuntimeException.class)
                .verify();

        verify(valueOperations).increment(ORDER_ID_KEY);
    }

    @Test
    void checkHealth_ShouldReturnTrueWhenRedisIsAvailable() {
        // Given - Solo configuramos el comportamiento en este test
        when(redisTemplate.hasKey(ORDER_ID_KEY)).thenReturn(Mono.just(true));

        // When
        Mono<Boolean> healthCheckMono = idGenerator.checkHealth();

        // Then
        StepVerifier.create(healthCheckMono)
                .expectNext(true)
                .verifyComplete();

        verify(redisTemplate).hasKey(ORDER_ID_KEY);
    }

    @Test
    void checkHealth_ShouldReturnTrueWhenRedisIsDownButFallbackEnabled() {
        // Given
        when(redisTemplate.hasKey(ORDER_ID_KEY)).thenReturn(Mono.error(new RuntimeException("Redis error")));
        ReflectionTestUtils.setField(idGenerator, "redisFallbackEnabled", true);

        // When
        Mono<Boolean> healthCheckMono = idGenerator.checkHealth();

        // Then
        StepVerifier.create(healthCheckMono)
                .expectNext(true)
                .verifyComplete();

        verify(redisTemplate).hasKey(ORDER_ID_KEY);
    }

    @Test
    void checkHealth_ShouldReturnFalseWhenRedisIsDownAndFallbackDisabled() {
        // Given
        when(redisTemplate.hasKey(ORDER_ID_KEY)).thenReturn(Mono.error(new RuntimeException("Redis error")));
        ReflectionTestUtils.setField(idGenerator, "redisFallbackEnabled", false);

        // When
        Mono<Boolean> healthCheckMono = idGenerator.checkHealth();

        // Then
        StepVerifier.create(healthCheckMono)
                .expectNext(false)
                .verifyComplete();

        verify(redisTemplate).hasKey(ORDER_ID_KEY);
    }

    @Test
    void clearIdRegistry_ShouldClearTheRegistry() {
        // Given
        ConcurrentHashMap<String, Boolean> testRegistry = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(idGenerator, "idRegistry", testRegistry);

        // Add some items
        testRegistry.put("test1", Boolean.TRUE);
        testRegistry.put("test2", Boolean.TRUE);

        // When
        idGenerator.clearIdRegistry();

        // Then
        assertTrue(testRegistry.isEmpty(), "Registry should be empty after clearing");
    }

    @Test
    void extractComponents_ShouldReturnCorrectValues() {
        // Given
        final long currentTime = Instant.now().toEpochMilli();
        final int nodeId = TEST_NODE_ID;
        final int sequence = 123;

        // Manually create an ID with known components
        final long orderId = ((currentTime << 22) | (nodeId << 12) | sequence);

        // When
        long extractedTimestamp = idGenerator.extractTimestampFromOrderId(orderId);
        int extractedNodeId = idGenerator.extractNodeIdFromOrderId(orderId);
        int extractedSequence = idGenerator.extractSequenceFromOrderId(orderId);

        // Then
        assertEquals(currentTime, extractedTimestamp, "Extracted timestamp should match original");
        assertEquals(nodeId, extractedNodeId, "Extracted nodeId should match original");
        assertEquals(sequence, extractedSequence, "Extracted sequence should match original");
    }

    @Test
    void validateStringId_ShouldThrowExceptionWhenIdIsNull() {
        // Invocar el método privado validateStringId a través de reflection
        Exception exception = assertThrows(IllegalStateException.class, () -> {
            ReflectionTestUtils.invokeMethod(idGenerator, "validateStringId",
                    new Object[] { null, "Test Field" });
        });

        assertTrue(exception.getMessage().contains("cannot be null"));
    }

    @Test
    void validateStringId_ShouldThrowExceptionWhenIdIsTooLong() {
        // Crear un ID demasiado largo
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longId.append("a");
        }

        // Invocar el método privado validateStringId a través de reflection
        Exception exception = assertThrows(IllegalStateException.class, () -> {
            ReflectionTestUtils.invokeMethod(idGenerator, "validateStringId",
                    new Object[] { longId.toString(), "Test Field" });
        });

        assertTrue(exception.getMessage().contains("exceeds maximum"));
    }
}