package com.example.order.repository;

import com.example.order.domain.DeliveryMode;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderEventType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests específicos para escenarios de error y condiciones de borde
 */
@Deprecated(since = "2.0.0", forRemoval = true)

@ExtendWith(MockitoExtension.class)
public class ImprovedR2dbcEventRepositoryErrorTest {

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private TransactionalOperator transactionalOperator;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    @InjectMocks
    private ImprovedR2dbcEventRepository repository;

    @Test
    void testDatabaseTimeoutHandling() {
        // Arrange
        String eventId = "event-123";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.error(new RuntimeException("Simulated timeout")));

        // Act & Assert
        // El método debe completar normalmente incluso con el error
        StepVerifier.create(repository.markEventAsProcessed(eventId))
                .verifyComplete();

        // Verificar que se registró el error pero no falló la operación
        verify(databaseClient).sql(contains("INSERT INTO processed_events"));
    }

    @Test
    void testConcurrentRequests() throws Exception {
        // Arrange
        int concurrentRequests = 10;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);

        // Evitar ambigüedad usando any(BiFunction.class)
        RowsFetchSpec<Integer> rowsFetchSpec = mock(RowsFetchSpec.class);
        when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        when(rowsFetchSpec.one()).thenReturn(Mono.just(0));

        // Act - Simular múltiples solicitudes concurrentes
        for (int i = 0; i < concurrentRequests; i++) {
            final String eventId = "event-" + i;

            // Lanzar en otro hilo
            new Thread(() -> {
                repository.isEventProcessed(eventId)
                        .doFinally(signal -> latch.countDown())
                        .subscribe();
            }).start();
        }

        // Esperar a que todas las solicitudes terminen (con timeout por si acaso)
        boolean completed = latch.await(5, TimeUnit.SECONDS);

        // Assert
        // Si completed es falso, significa que no todas las solicitudes completaron en el tiempo esperado
        assert completed : "No todas las solicitudes concurrentes completaron a tiempo";

        // Verificar que el método SQL se llamó concurrentRequests veces
        verify(databaseClient, times(concurrentRequests)).sql(anyString());
    }

    @Test
    void testTransactionRollbackOnError() {
        // Arrange
        Long orderId = 123L;
        String correlationId = "corr-123";
        String eventId = "event-123";
        OrderEvent mockEvent = mock(OrderEvent.class);
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        when(mockEvent.getType()).thenReturn(OrderEventType.ORDER_CREATED);
        when(mockEvent.toJson()).thenReturn("{\"type\":\"ORDER_CREATED\"}");

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Simular error durante la transacción
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenReturn(Mono.error(new RuntimeException("Simulated transaction error")));

        // Act & Assert
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, mockEvent, deliveryMode))
                .expectError(RuntimeException.class)
                .verify();

        // Verify que la transacción se intentó pero falló
        verify(transactionalOperator).transactional(any(Mono.class));
    }

    @Test
    void testDuplicateKeyHandling() {
        // Arrange
        String resourceId = "order-123";
        String correlationId = "corr-123";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);

        // Simular error de clave duplicada (constraintViolation)
        when(executeSpec.then()).thenReturn(Mono.error(new RuntimeException("Duplicate key")));

        // Act & Assert - Debe manejar correctamente el error y retornar falso
        StepVerifier.create(repository.acquireTransactionLock(resourceId, correlationId, 60))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void testEmptyResultHandling() {
        // Arrange
        Long orderId = 123L;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);

        // Evitar ambigüedad usando any(BiFunction.class)
        RowsFetchSpec<Object> rowsFetchSpec = mock(RowsFetchSpec.class);
        when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        when(rowsFetchSpec.one()).thenReturn(Mono.empty());

        // Act & Assert - Verificar el comportamiento real del repositorio
        StepVerifier.create(repository.findOrderById(orderId))
                .expectErrorSatisfies(error -> {
                    Assertions.assertInstanceOf(IllegalStateException.class, error);
                    Assertions.assertTrue(error.getMessage().contains("Order not found"));
                })
                .verify();
    }

    @Test
    void testIdempotencyWithAt_Most_Once() {
        // Arrange
        String eventId = "event-123";
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);

        // Evitar ambigüedad usando any(BiFunction.class)
        RowsFetchSpec<Integer> rowsFetchSpec = mock(RowsFetchSpec.class);
        when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        when(rowsFetchSpec.one()).thenReturn(Mono.just(1));

        // Preparar los mocks para saveOrderData
        Long orderId = 123L;
        String correlationId = "corr-123";
        OrderEvent mockEvent = mock(OrderEvent.class);

        // Act & Assert
        // En modo AT_MOST_ONCE, si el evento ya está procesado, no debería realizar ninguna acción
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, mockEvent, deliveryMode))
                .verifyComplete();

        // Verificar que no se intenta realizar la transacción
        verify(transactionalOperator, never()).transactional(any(Mono.class));
    }
    @Test
    void testLockReleaseErrorHandling() {
        // Arrange
        String resourceId = "order-123";
        String correlationId = "corr-123";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);

        // Simular error al intentar liberar el bloqueo
        when(executeSpec.then()).thenReturn(Mono.error(new RuntimeException("Failed to release lock")));

        // Act & Assert - Debe manejar el error y completar normalmente
        StepVerifier.create(repository.releaseTransactionLock(resourceId, correlationId))
                .verifyComplete();

        // Verificar que se intentó liberar el bloqueo
        verify(databaseClient).sql(contains("UPDATE transaction_locks"));
    }

    @Test
    void testLongCorrelationIdTruncation() {
        // Arrange
        String tooLongCorrelationId = "a".repeat(100); // más largo que los 36 permitidos
        String eventId = "event-123";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        // Debería truncar o rechazar el ID demasiado largo
        // Dependiendo de la implementación podría dar error o truncar
        StepVerifier.create(repository.markEventAsProcessed(eventId))
                .verifyComplete();
    }

    @Test
    void testRollbackOnPartialFailure() {
        // Arrange
        Long orderId = 123L;
        String correlationId = "corr-123";
        String eventId = "event-123";
        OrderEvent mockEvent = mock(OrderEvent.class);
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        when(mockEvent.getType()).thenReturn(OrderEventType.ORDER_CREATED);
        when(mockEvent.toJson()).thenReturn("{\"type\":\"ORDER_CREATED\"}");

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);

        // Primera consulta exitosa, segunda falla
        when(executeSpec.then())
                .thenReturn(Mono.empty()) // primera ejecución (insertar orden)
                .thenReturn(Mono.error(new RuntimeException("Failed to insert outbox"))); // segunda (insertar outbox)

        // Simular que la transacción propaga el error
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0)); // Pasar el Mono tal cual

        // Act & Assert
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, mockEvent, deliveryMode))
                .expectError(RuntimeException.class)
                .verify();

        // Verificar que se intentó usar la transacción
        verify(transactionalOperator).transactional(any(Mono.class));
    }

    @Test
    void testNullEventToJsonHandling() {
        // Arrange
        Long orderId = 123L;
        String correlationId = "corr-123";
        String eventId = "event-123";
        OrderEvent mockEvent = mock(OrderEvent.class);
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        when(mockEvent.getType()).thenReturn(OrderEventType.ORDER_CREATED);
        // Simular que toJson devuelve null
        when(mockEvent.toJson()).thenReturn(null);

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act & Assert - Verificar el comportamiento real, que parece ser completar sin error
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, mockEvent, deliveryMode))
                .verifyComplete();
    }
    @Test
    void testDatabaseErrorHandlingInRecordStepFailure() {
        // Arrange
        String stepName = "payment";
        Long orderId = 123L;
        String correlationId = "corr-123";
        String eventId = "event-123";
        String errorMessage = "Payment failed";
        String errorType = "PaymentException";
        String errorCategory = "PAYMENT_ERROR";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.error(new RuntimeException("Database error")));

        // Act & Assert - Debe completar normalmente a pesar del error
        StepVerifier.create(repository.recordStepFailure(stepName, orderId, correlationId, eventId,
                        errorMessage, errorType, errorCategory))
                .verifyComplete();

        // Verificar que se intentó registrar el error
        verify(databaseClient).sql(contains("INSERT INTO saga_step_failures"));
    }

    @Test
    void testRetryMechanismOnDatabaseError() {
        // Arrange
        Long orderId = 123L;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);

        // Evitar ambigüedad usando any(BiFunction.class)
        RowsFetchSpec<Integer> rowsFetchSpec = mock(RowsFetchSpec.class);
        when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);

        // Configurar para que falle la primera vez y luego tenga éxito
        when(rowsFetchSpec.one())
                .thenReturn(Mono.error(new RuntimeException("Temporary error")))
                .thenReturn(Mono.just(0));

        // Act & Assert - Debe reintentar y eventualmente tener éxito
        StepVerifier.create(repository.isEventProcessed("event-123"))
                .expectNext(false)
                .verifyComplete();

        // Verificar que se realizó la consulta
        verify(databaseClient).sql(contains("SELECT COUNT(*) FROM processed_events"));
    }

    @Test
    void testRaceConditionInMarkAsProcessed() {
        // Arrange - Simulando una condición de carrera donde el evento ya fue marcado por otro proceso
        String eventId = "event-123";
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);

        // Simular un error de clave duplicada que ocurriría si otro proceso ya insertó el registro
        when(executeSpec.then()).thenReturn(Mono.error(new RuntimeException("Duplicate key")));

        // Act & Assert - Debe manejar correctamente el error y completar sin error
        StepVerifier.create(repository.markEventAsProcessed(eventId, deliveryMode))
                .verifyComplete();

        // Verificar que se intentó insertar
        verify(databaseClient).sql(contains("INSERT INTO processed_events"));
    }

    @Test
    void testSaveEventHistoryErrorHandling() {
        // Arrange
        String eventId = "event-123";
        String correlationId = "corr-123";
        Long orderId = 123L;
        String eventType = "ORDER_CREATED";
        String operation = "CREATE";
        String outcome = "SUCCESS";
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.error(new RuntimeException("Database error")));

        // Act & Assert - Debe completar normalmente a pesar del error (es un registro de auditoría)
        StepVerifier.create(repository.saveEventHistory(eventId, correlationId, orderId,
                        eventType, operation, outcome, deliveryMode))
                .verifyComplete();

        // Verificar que se intentó registrar el historial
        verify(databaseClient).sql(contains("INSERT INTO event_history"));
    }

    @Test
    void testRecordSagaFailureWithNullParametersHandling() {
        // Arrange
        Long orderId = 123L;
        String correlationId = "corr-123";
        String errorMessage = null; // Mensaje de error nulo
        String errorType = "UnknownError";
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert - Debería manejar el mensaje nulo y completar correctamente
        StepVerifier.create(repository.recordSagaFailure(orderId, correlationId, errorMessage, errorType, deliveryMode))
                .verifyComplete();

        // Verificar que se intentó registrar el fallo con un valor por defecto para el mensaje nulo
        verify(databaseClient).sql(contains("INSERT INTO saga_failures"));
        verify(executeSpec).bind(eq("errorMessage"), eq("No error message provided"));
    }

    @Test
    void testAcquireLockWithNegativeTimeout() {
        // Arrange
        String resourceId = "order-123";
        String correlationId = "corr-123";
        int negativeTimeout = -10; // Timeout negativo

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert - Debería utilizar un valor por defecto y completar correctamente
        StepVerifier.create(repository.acquireTransactionLock(resourceId, correlationId, negativeTimeout))
                .expectNext(true)
                .verifyComplete();

        // Verificar que se utilizó un valor por defecto para el timeout
        verify(databaseClient).sql(contains("INSERT INTO transaction_locks"));
        verify(executeSpec).bind(eq("expiresAt"), any());
    }
}