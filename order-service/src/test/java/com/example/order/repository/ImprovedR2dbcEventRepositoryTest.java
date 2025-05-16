package com.example.order.repository;

import com.example.order.domain.Order;
import com.example.order.domain.DeliveryMode;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.function.BiFunction;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImprovedR2dbcEventRepositoryTest {

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private TransactionalOperator transactionalOperator;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    @Mock
    private RowsFetchSpec<Integer> rowsFetchSpec;

    @Mock
    private FetchSpec<Void> voidFetchSpec;

    @Mock
    private RowsFetchSpec<Order> orderRowsFetchSpec;

    private ImprovedR2dbcEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ImprovedR2dbcEventRepository(databaseClient, transactionalOperator);
    }

    @Test
    void isEventProcessed_returnsTrue_whenEventExists() {
        // Arrange
        String eventId = "event-123";
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        // Utilizar ArgumentMatchers.any() para evitar ambigüedad
        when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        when(rowsFetchSpec.one()).thenReturn(Mono.just(1));

        // Act & Assert
        StepVerifier.create(repository.isEventProcessed(eventId))
                .expectNext(true)
                .verifyComplete();

        verify(databaseClient).sql(contains("SELECT COUNT(*) FROM processed_events"));
        verify(executeSpec).bind("eventId", eventId);
    }

    @Test
    void isEventProcessed_returnsTrue_withDeliveryMode() {
        // Arrange
        String eventId = "event-123";
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        // Utilizar ArgumentMatchers.any() para evitar ambigüedad
        when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        when(rowsFetchSpec.one()).thenReturn(Mono.just(1));

        // Act & Assert
        StepVerifier.create(repository.isEventProcessed(eventId, deliveryMode))
                .expectNext(true)
                .verifyComplete();

        verify(databaseClient).sql(contains("SELECT COUNT(*) FROM processed_events"));
        verify(executeSpec).bind("eventId", eventId);
        verify(executeSpec).bind("deliveryMode", deliveryMode.name());
    }

    @Test
    void isEventProcessed_returnsFalse_whenEventDoesNotExist() {
        // Arrange
        String eventId = "event-123";
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        // Utilizar ArgumentMatchers.any() para evitar ambigüedad
        when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        when(rowsFetchSpec.one()).thenReturn(Mono.just(0));

        // Act & Assert
        StepVerifier.create(repository.isEventProcessed(eventId))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void isEventProcessed_returnsError_whenEventIdIsNull() {
        // Act & Assert
        StepVerifier.create(repository.isEventProcessed(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void isEventProcessed_returnsDefaultFalse_onDatabaseError() {
        // Arrange
        String eventId = "event-123";
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        // Utilizar ArgumentMatchers.any() para evitar ambigüedad
        when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        when(rowsFetchSpec.one()).thenReturn(Mono.error(new RuntimeException("DB Error")));

        // Act & Assert
        StepVerifier.create(repository.isEventProcessed(eventId))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void markEventAsProcessed_completes_whenSuccessful() {
        // Arrange
        String eventId = "event-123";
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.markEventAsProcessed(eventId))
                .verifyComplete();

        verify(databaseClient).sql(contains("INSERT INTO processed_events"));
        verify(executeSpec).bind("eventId", eventId);
    }

    @Test
    void markEventAsProcessed_withDeliveryMode_completes_whenSuccessful() {
        // Arrange
        String eventId = "event-123";
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.markEventAsProcessed(eventId, deliveryMode))
                .verifyComplete();

        verify(databaseClient).sql(contains("INSERT INTO processed_events"));
        verify(executeSpec).bind("eventId", eventId);
        verify(executeSpec).bind("deliveryMode", deliveryMode.name());
    }

    @Test
    void markEventAsProcessed_returnsError_whenEventIdIsNull() {
        // Act & Assert
        StepVerifier.create(repository.markEventAsProcessed(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void findOrderById_returnsOrder_whenOrderExists() {
        // Arrange
        Long orderId = 123L;
        Order mockOrder = new Order(orderId, "pending", "corr-123");

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        // Utilizar ArgumentMatchers.any() para evitar ambigüedad
        when(executeSpec.map(any(BiFunction.class))).thenReturn(orderRowsFetchSpec);
        when(orderRowsFetchSpec.one()).thenReturn(Mono.just(mockOrder));

        // Act & Assert
        StepVerifier.create(repository.findOrderById(orderId))
                .expectNext(mockOrder)
                .verifyComplete();

        verify(databaseClient).sql(contains("SELECT id, status, correlation_id"));
        verify(executeSpec).bind("id", orderId);
    }

    @Test
    void findOrderById_returnsError_whenOrderIdIsNull() {
        // Act & Assert
        StepVerifier.create(repository.findOrderById(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void findOrderById_returnsError_whenOrderNotFound() {
        // Arrange
        Long orderId = 123L;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        // Utilizar ArgumentMatchers.any() para evitar ambigüedad
        when(executeSpec.map(any(BiFunction.class))).thenReturn(orderRowsFetchSpec);
        when(orderRowsFetchSpec.one()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.findOrderById(orderId))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void saveOrderData_atLeastOnce_completes_whenSuccessful() {
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
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act & Assert
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, mockEvent, deliveryMode))
                .verifyComplete();

        verify(databaseClient, times(3)).sql(anyString());
        verify(executeSpec, atLeast(8)).bind(anyString(), any());
        verify(executeSpec).bind("deliveryMode", deliveryMode.name());
        verify(transactionalOperator).transactional(any(Mono.class));
    }

    @Test
    void saveOrderData_atMostOnce_completes_whenSuccessful() {
        // Arrange
        Long orderId = 123L;
        String correlationId = "corr-123";
        String eventId = "event-123";
        OrderEvent mockEvent = mock(OrderEvent.class);
        DeliveryMode deliveryMode = DeliveryMode.AT_MOST_ONCE;

        when(mockEvent.getType()).thenReturn(OrderEventType.ORDER_CREATED);
        when(mockEvent.toJson()).thenReturn("{\"type\":\"ORDER_CREATED\"}");

        // Para AT_MOST_ONCE, primero verificamos si ya está procesado
        when(databaseClient.sql(contains("SELECT COUNT(*)"))).thenReturn(executeSpec);
        when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        when(rowsFetchSpec.one()).thenReturn(Mono.just(0)); // No está procesado todavía

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act & Assert
        StepVerifier.create(repository.saveOrderData(orderId, correlationId, eventId, mockEvent, deliveryMode))
                .verifyComplete();

        verify(transactionalOperator).transactional(any(Mono.class));
    }

    @Test
    void saveOrderData_returnsError_whenParametersAreNull() {
        // Act & Assert
        StepVerifier.create(repository.saveOrderData(null, "corr", "event", mock(OrderEvent.class)))
                .expectError(IllegalArgumentException.class)
                .verify();

        StepVerifier.create(repository.saveOrderData(123L, null, "event", mock(OrderEvent.class)))
                .expectError(IllegalArgumentException.class)
                .verify();

        StepVerifier.create(repository.saveOrderData(123L, "corr", null, mock(OrderEvent.class)))
                .expectError(IllegalArgumentException.class)
                .verify();

        StepVerifier.create(repository.saveOrderData(123L, "corr", "event", null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void updateOrderStatus_returnsUpdatedOrder_whenSuccessful() {
        // Arrange
        Long orderId = 123L;
        String status = "completed";
        String correlationId = "corr-123";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.updateOrderStatus(orderId, status, correlationId))
                .expectNextMatches(order ->
                        order.id().equals(orderId) &&
                                order.status().equals(status) &&
                                order.correlationId().equals(correlationId))
                .verifyComplete();

        verify(databaseClient, times(2)).sql(anyString());
        verify(executeSpec, atLeast(5)).bind(anyString(), any());
    }

    @Test
    void updateOrderStatus_returnsError_whenParametersAreNull() {
        // Act & Assert
        StepVerifier.create(repository.updateOrderStatus(null, "status", "corr"))
                .expectError(IllegalArgumentException.class)
                .verify();

        StepVerifier.create(repository.updateOrderStatus(123L, null, "corr"))
                .expectError(IllegalArgumentException.class)
                .verify();

        StepVerifier.create(repository.updateOrderStatus(123L, "status", null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void insertStatusAuditLog_completes_whenSuccessful() {
        // Arrange
        Long orderId = 123L;
        String status = "completed";
        String correlationId = "corr-123";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.insertStatusAuditLog(orderId, status, correlationId))
                .verifyComplete();

        verify(databaseClient).sql(contains("INSERT INTO order_status_history"));
        verify(executeSpec).bind("orderId", orderId);
        verify(executeSpec).bind("status", status);
        verify(executeSpec).bind("correlationId", correlationId);
    }

    @Test
    void insertCompensationLog_completes_whenSuccessful() {
        // Arrange
        String stepName = "payment";
        Long orderId = 123L;
        String correlationId = "corr-123";
        String eventId = "event-123";
        String status = "compensated";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.insertCompensationLog(stepName, orderId, correlationId, eventId, status))
                .verifyComplete();

        verify(databaseClient).sql(contains("INSERT INTO compensation_log"));
        verify(executeSpec).bind("stepName", stepName);
        verify(executeSpec).bind("orderId", orderId);
        verify(executeSpec).bind("correlationId", correlationId);
        verify(executeSpec).bind("eventId", eventId);
        verify(executeSpec).bind("status", status);
    }

    @Test
    void recordStepFailure_completes_whenSuccessful() {
        // Arrange
        String stepName = "payment";
        Long orderId = 123L;
        String correlationId = "corr-123";
        String eventId = "event-123";
        String errorMessage = "Payment failed";
        String errorType = "PaymentException";
        String errorCategory = "EXTERNAL_SERVICE_ERROR";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.recordStepFailure(
                        stepName, orderId, correlationId, eventId, errorMessage, errorType, errorCategory))
                .verifyComplete();

        verify(databaseClient).sql(contains("INSERT INTO saga_step_failures"));
        verify(executeSpec).bind("stepName", stepName);
        verify(executeSpec).bind("orderId", orderId);
        verify(executeSpec).bind("correlationId", correlationId);
        verify(executeSpec).bind("eventId", eventId);
        verify(executeSpec).bind("errorMessage", errorMessage);
        verify(executeSpec).bind("errorType", errorType);
        verify(executeSpec).bind("errorCategory", errorCategory);
    }

    @Test
    void recordStepFailure_handlesNullErrorCategory() {
        // Arrange
        String stepName = "payment";
        Long orderId = 123L;
        String correlationId = "corr-123";
        String eventId = "event-123";
        String errorMessage = "Payment failed";
        String errorType = "PaymentException";
        String errorCategory = null; // Probar con categoría nula

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.recordStepFailure(
                        stepName, orderId, correlationId, eventId, errorMessage, errorType, errorCategory))
                .verifyComplete();

        verify(databaseClient).sql(contains("INSERT INTO saga_step_failures"));
        verify(executeSpec).bind("errorCategory", "UNKNOWN"); // Verificar que se usa un valor por defecto
    }

    @Test
    void recordSagaFailure_completes_whenSuccessful() {
        // Arrange
        Long orderId = 123L;
        String correlationId = "corr-123";
        String errorMessage = "Saga failed";
        String errorType = "SagaException";
        DeliveryMode deliveryMode = DeliveryMode.AT_LEAST_ONCE;

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.recordSagaFailure(orderId, correlationId, errorMessage, errorType, deliveryMode))
                .verifyComplete();

        verify(databaseClient).sql(contains("INSERT INTO saga_failures"));
        verify(executeSpec).bind("orderId", orderId);
        verify(executeSpec).bind("correlationId", correlationId);
        verify(executeSpec).bind("errorMessage", errorMessage);
        verify(executeSpec).bind("errorType", errorType);
        verify(executeSpec).bind("deliveryMode", deliveryMode.name());
    }

    @Test
    void saveEventHistory_completes_whenSuccessful() {
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
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.saveEventHistory(eventId, correlationId, orderId, eventType, operation, outcome, deliveryMode))
                .verifyComplete();

        verify(databaseClient).sql(contains("INSERT INTO event_history"));
        verify(executeSpec).bind("eventId", eventId);
        verify(executeSpec).bind("correlationId", correlationId);
        verify(executeSpec).bind("orderId", orderId);
        verify(executeSpec).bind("eventType", eventType);
        verify(executeSpec).bind("operation", operation);
        verify(executeSpec).bind("outcome", outcome);
        verify(executeSpec).bind("deliveryMode", deliveryMode.name());
    }

    @Test
    void acquireTransactionLock_returnsTrue_whenSuccessful() {
        // Arrange
        String resourceId = "order-123";
        String correlationId = "corr-123";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.acquireTransactionLock(resourceId, correlationId, 60))
                .expectNext(true)
                .verifyComplete();

        verify(databaseClient).sql(contains("INSERT INTO transaction_locks"));
        verify(executeSpec).bind("resourceId", resourceId);
        verify(executeSpec).bind("correlationId", correlationId);
    }

    @Test
    void acquireTransactionLock_returnsFalse_whenLockFails() {
        // Arrange
        String resourceId = "order-123";
        String correlationId = "corr-123";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.error(new RuntimeException("Duplicate key")));

        // Act & Assert
        StepVerifier.create(repository.acquireTransactionLock(resourceId, correlationId, 60))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void releaseTransactionLock_completes_whenSuccessful() {
        // Arrange
        String resourceId = "order-123";
        String correlationId = "corr-123";

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(repository.releaseTransactionLock(resourceId, correlationId))
                .verifyComplete();

        verify(databaseClient).sql(contains("UPDATE transaction_locks"));
        verify(executeSpec).bind("resourceId", resourceId);
        verify(executeSpec).bind("correlationId", correlationId);
    }
}