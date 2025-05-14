package com.example.order.service.unit;

import com.example.order.config.CircuitBreakerCategory;
import com.example.order.events.EventTopics;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.*;
import com.example.order.utils.ReactiveUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("unit")
@MockitoSettings(strictness = Strictness.LENIENT)
class SagaOrchestratorAtLeastOnceUnitTest {

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private CompensationManager compensationManager;

    @Mock
    private TransactionalOperator transactionalOperator;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private ResilienceManager resilienceManager;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private Counter counter;

    @Mock
    private Timer timer;

    @Mock
    private Timer.Sample timerSample;

    private static final Long ORDER_ID = 1234L;
    private static final String CORRELATION_ID = "corr-123";
    private static final String EVENT_ID = "event-123";
    private static final String EXTERNAL_REF = "ext-123";
    private static final int QUANTITY = 10;
    private static final double AMOUNT = 100.0;

    // Variables para almacenar los mocks estáticos y poder cerrarlos correctamente
    private MockedStatic<Timer> timerMock;
    private MockedStatic<ReactiveUtils> reactiveUtilsMock;

    private SagaOrchestratorAtLeastOnceImpl sagaOrchestrator;

    @BeforeEach
    void setUp() {
        // Inicializar la instancia real con los mocks
        sagaOrchestrator = new SagaOrchestratorAtLeastOnceImpl(
                databaseClient,
                transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                inventoryService,
                compensationManager
        );

        // Mock básico de DatabaseClient para que no falle
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        // Mock para TransactionalOperator
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Mock para ResilienceManager
        when(resilienceManager.applyResilience(anyString()))
                .thenReturn(Function.identity());
        when(resilienceManager.applyResilience(any(CircuitBreakerCategory.class)))
                .thenReturn(Function.identity());

        // Mock para métrica
        when(meterRegistry.counter(anyString(), any(String.class), any(String.class)))
                .thenReturn(counter);
        when(meterRegistry.timer(anyString(), any(Iterable.class)))
                .thenReturn(timer);

        // Mock para generación de IDs
        when(idGenerator.generateOrderId()).thenReturn(ORDER_ID);
        when(idGenerator.generateCorrelationId()).thenReturn(CORRELATION_ID);
        when(idGenerator.generateEventId()).thenReturn(EVENT_ID);
        when(idGenerator.generateExternalReference()).thenReturn(EXTERNAL_REF);

        // Mock Timer.start
        timerMock = mockStatic(Timer.class);
        timerMock.when(() -> Timer.start(any(MeterRegistry.class)))
                .thenReturn(timerSample);

        // Mock ReactiveUtils
        reactiveUtilsMock = mockStatic(ReactiveUtils.class);

        reactiveUtilsMock.when(() -> ReactiveUtils.withContextAndMetrics(
                        anyMap(),
                        any(),
                        any(MeterRegistry.class),
                        anyString(),
                        any(Tag[].class)))
                .thenAnswer(invocation -> {
                    Supplier<Mono<?>> supplier = invocation.getArgument(1);
                    return supplier.get();
                });

        reactiveUtilsMock.when(() -> ReactiveUtils.withDiagnosticContext(
                        anyMap(),
                        any()))
                .thenAnswer(invocation -> {
                    Supplier<Mono<?>> supplier = invocation.getArgument(1);
                    return supplier.get();
                });

        reactiveUtilsMock.when(() -> ReactiveUtils.createContext(any(String[].class)))
                .thenReturn(Map.of());

        // Mock para publicación de eventos
        when(eventPublisher.publishEvent(any(OrderEvent.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    OrderEvent event = inv.getArgument(0);
                    return Mono.just(EventPublishOutcome.success(event));
                });

        // Mock para inventory service
        when(inventoryService.reserveStock(anyLong(), anyInt())).thenReturn(Mono.empty());
    }

    @AfterEach
    void tearDown() {
        if (timerMock != null) {
            timerMock.close();
        }
        if (reactiveUtilsMock != null) {
            reactiveUtilsMock.close();
        }
    }

    @Test
    void testExecuteStep_NullStep() {
        // Este es un caso simple que no depende de metodología interna
        Mono<OrderEvent> result = sagaOrchestrator.executeStep(null);

        StepVerifier.create(result)
                .expectErrorMatches(e -> e instanceof IllegalArgumentException &&
                        e.getMessage().equals("SagaStep cannot be null"))
                .verify();
    }

    @Test
    void testCreateFailedEvent() {
        String reason = "Test failure reason";
        String externalRef = "external-123";

        Mono<Void> result = sagaOrchestrator.createFailedEvent(reason, externalRef);

        StepVerifier.create(result)
                .verifyComplete();

        // Verificar que se publicó un evento de fallo - este tipo de verificación es razonable
        verify(eventPublisher).publishEvent(any(OrderFailedEvent.class), eq("failedEvent"), eq(EventTopics.ORDER_FAILED.getTopic()));
    }
}