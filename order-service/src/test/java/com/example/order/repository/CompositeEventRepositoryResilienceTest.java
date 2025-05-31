package com.example.order.repository;

import com.example.order.OrderServiceApplication;
import com.example.order.config.DatabaseConfig; // Added
import com.example.order.config.EventRepositoryConfig; // Added
import com.example.order.config.ResilienceConfig; // Added
import com.example.order.actuator.StrategyConfigurationListener;
import com.example.order.actuator.StrategyConfigurationManager;
import com.example.order.config.TestStrategyConfiguration;
import com.example.order.domain.DeliveryMode;
import com.example.order.repository.events.ProcessedEventRepository;
import com.example.order.service.OrderService;
import com.example.order.service.SagaOrchestrator;
import org.springframework.boot.test.context.TestConfiguration; // Added
import org.springframework.context.annotation.Bean; // Added
import org.springframework.context.annotation.Import; // Added
import org.springframework.context.annotation.Primary; // Added
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test de integración para probar la lógica de resiliencia del CompositeEventRepository.
 *
 * Este test usa Spring Boot Test para cargar el contexto completo con la configuración
 * de Resilience4j desde application-integration-test.yml
 *
 * Nota: Usa @MockitoBean (Spring Boot 3.4+) en lugar de @MockBean (deprecado)
 */
@SpringBootTest(classes = {
        OrderServiceApplication.class,
        TestStrategyConfiguration.class
}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration-test")
@Import(TestStrategyConfiguration.class)

public class CompositeEventRepositoryResilienceTest {

    @Autowired
    private EventRepository eventRepository;

    // Mock único para resolver el conflicto
    @MockitoBean
    @Qualifier("dynamicOrderService")
    private OrderService orderService;

    // Todos tus otros mocks...
    @MockitoBean
    private StrategyConfigurationManager strategyConfigurationManager;

    @MockitoBean
    @Qualifier("atLeastOnce")
    private SagaOrchestrator mockAtLeastOnceSagaOrchestrator;

    @MockitoBean
    @Qualifier("sagaOrchestratorImpl2")
    private SagaOrchestrator mockAtMostOnceSagaOrchestrator;

    @MockitoBean
    private ProcessedEventRepository processedEventRepository;

    @MockitoBean
    private com.example.order.repository.orders.OrderRepository orderRepository;

    @MockitoBean
    private com.example.order.repository.saga.SagaFailureRepository sagaFailureRepository;

    @MockitoBean
    private com.example.order.repository.events.EventHistoryRepository eventHistoryRepository;

    @MockitoBean
    private com.example.order.repository.transactions.TransactionLockRepository transactionLockRepository;

    @BeforeEach
    void setUp() {
        reset(processedEventRepository, orderRepository, sagaFailureRepository,
                eventHistoryRepository, transactionLockRepository);
    }
    @Test
    @DisplayName("Test de resiliencia: Debe reintentar operaciones tras fallos transitorios")
    void testRetryOnTransientFailures() {
        String eventId = "test-retry-event";

        // Usar AtomicInteger para contar las llamadas reales
        AtomicInteger callCount = new AtomicInteger(0);

        when(processedEventRepository.isEventProcessed(eventId))
                .thenAnswer(invocation -> {
                    int count = callCount.incrementAndGet();
                    if (count <= 2) {
                        // Las primeras 2 llamadas fallan
                        return Mono.error(new RuntimeException("Database connection error"));
                    } else {
                        // A partir de la 3ª llamada, éxito
                        return Mono.just(true);
                    }
                });

        // Verificar que eventualmente tiene éxito
        StepVerifier.create(eventRepository.isEventProcessed(eventId))
                .expectNext(true)
                .verifyComplete();

        // Con max-attempts: 3 desde application-integration-test.yml
        // Debería hacer: 1 intento inicial + 2 reintentos = 3 llamadas totales
        verify(processedEventRepository, times(3)).isEventProcessed(eventId);
    }

    @Test
    @DisplayName("Test de resiliencia: Debe fallar después del número máximo de reintentos")
    void testFailAfterMaxRetries() {
        String eventId = "test-max-retries-event";

        // Configurar el mock para fallar siempre
        when(processedEventRepository.isEventProcessed(eventId))
                .thenReturn(Mono.error(new RuntimeException("Database connection error")));

        // Verificar que eventualmente falla después de los reintentos
        StepVerifier.create(eventRepository.isEventProcessed(eventId))
                .expectErrorMatches(e -> {
                    // Verificar que es un error de reintentos agotados
                    String message = e.getMessage();
                    return message != null && message.contains("Retries exhausted");
                })
                .verify(Duration.ofSeconds(10));

        // Con max-attempts: 3, debería hacer 3 llamadas (1 inicial + 2 reintentos)
        verify(processedEventRepository, times(3)).isEventProcessed(eventId);
    }

    @Test
    @DisplayName("Test de resiliencia: No debe reintentar errores no transitorios")
    void testNoRetryForNonTransientErrors() {
        String eventId = "test-non-transient-event";

        // Configurar el mock para generar un error no transitorio
        when(processedEventRepository.isEventProcessed(eventId))
                .thenReturn(Mono.error(new IllegalArgumentException("Invalid parameter")));

        // Verificar que no hay reintentos para errores no transitorios
        StepVerifier.create(eventRepository.isEventProcessed(eventId))
                .expectError(IllegalArgumentException.class)
                .verify(Duration.ofSeconds(5));

        // Verificar que solo se hizo una llamada (sin reintentos)
        verify(processedEventRepository, times(1)).isEventProcessed(eventId);
    }

    @Test
    @DisplayName("Test de diferentes tipos de errores transitorios")
    void testDifferentTransientErrors() {
        String eventId1 = "test-connection-error";
        String eventId2 = "test-timeout-error";
        String eventId3 = "test-transient-error";

        // Error de conexión (debería reintentar)
        when(processedEventRepository.isEventProcessed(eventId1))
                .thenReturn(Mono.error(new java.net.ConnectException("Connection refused")));

        // Error de timeout (debería reintentar)
        when(processedEventRepository.isEventProcessed(eventId2))
                .thenReturn(Mono.error(new java.util.concurrent.TimeoutException("Operation timed out")));

        // Error transitorio genérico (debería reintentar)
        when(processedEventRepository.isEventProcessed(eventId3))
                .thenReturn(Mono.error(new org.springframework.dao.TransientDataAccessException("Temporary failure") {}));

        // Verificar que todos estos errores se reintentan y eventualmente fallan después de los reintentos
        StepVerifier.create(eventRepository.isEventProcessed(eventId1))
                .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("Retries exhausted"))
                .verify(Duration.ofSeconds(8));

        StepVerifier.create(eventRepository.isEventProcessed(eventId2))
                .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("Retries exhausted"))
                .verify(Duration.ofSeconds(8));

        StepVerifier.create(eventRepository.isEventProcessed(eventId3))
                .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("Retries exhausted"))
                .verify(Duration.ofSeconds(8));

        // Con max-attempts: 3, cada error debería hacer 3 llamadas
        verify(processedEventRepository, times(3)).isEventProcessed(eventId1);
        verify(processedEventRepository, times(3)).isEventProcessed(eventId2);
        verify(processedEventRepository, times(3)).isEventProcessed(eventId3);
    }

    @Test
    @DisplayName("Test de validación: Debe validar parámetros correctamente")
    void testParameterValidation() {
        // Test con resourceId nulo
        StepVerifier.create(eventRepository.acquireTransactionLock(null, "corr-id", 10))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Test con resourceId vacío
        StepVerifier.create(eventRepository.acquireTransactionLock("", "corr-id", 10))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Test con correlationId nulo
        StepVerifier.create(eventRepository.acquireTransactionLock("resource-id", null, 10))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Verificar que no se llamó al repositorio subyacente
        verify(transactionLockRepository, never()).acquireTransactionLock(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Test de delegación: Debe delegar operaciones correctamente")
    void testOperationDelegation() {
        String eventId = "test-delegation-event";

        // Configurar mocks
        when(processedEventRepository.isEventProcessed(eventId, DeliveryMode.AT_LEAST_ONCE))
                .thenReturn(Mono.just(false));
        when(processedEventRepository.markEventAsProcessed(eventId, DeliveryMode.AT_LEAST_ONCE))
                .thenReturn(Mono.empty());

        // Test isEventProcessed con DeliveryMode
        StepVerifier.create(eventRepository.isEventProcessed(eventId, DeliveryMode.AT_LEAST_ONCE))
                .expectNext(false)
                .verifyComplete();

        // Test markEventAsProcessed con DeliveryMode
        StepVerifier.create(eventRepository.markEventAsProcessed(eventId, DeliveryMode.AT_LEAST_ONCE))
                .verifyComplete();

        // Verificar que se delegaron las llamadas correctamente
        verify(processedEventRepository).isEventProcessed(eventId, DeliveryMode.AT_LEAST_ONCE);
        verify(processedEventRepository).markEventAsProcessed(eventId, DeliveryMode.AT_LEAST_ONCE);
    }

    @Test
    @DisplayName("Test de operación exitosa: No debe reintentar operaciones exitosas")
    void testSuccessfulOperationNoRetry() {
        String eventId = "test-success-event";

        // Configurar el mock para tener éxito inmediatamente
        when(processedEventRepository.isEventProcessed(eventId))
                .thenReturn(Mono.just(true));

        // Verificar que la operación tiene éxito sin reintentos
        StepVerifier.create(eventRepository.isEventProcessed(eventId))
                .expectNext(true)
                .verifyComplete();

        // Verificar que solo se hizo una llamada (sin reintentos)
        verify(processedEventRepository, times(1)).isEventProcessed(eventId);
    }

    @Test
    @DisplayName("Test de timeout: Debe manejar timeouts correctamente")
    void testTimeoutHandling() {
        String eventId = "test-timeout-event";

        // Configurar el mock para simular timeout
        when(processedEventRepository.isEventProcessed(eventId))
                .thenReturn(Mono.delay(Duration.ofSeconds(30))
                        .then(Mono.just(true))); // Demora más que el timeout configurado

        // Verificar que la operación falla por timeout
        StepVerifier.create(eventRepository.isEventProcessed(eventId))
                .expectError() // Puede ser TimeoutException o RetryExhaustedException
                .verify(Duration.ofSeconds(10));

        // Verificar que se intentó al menos una vez
        verify(processedEventRepository, atLeast(1)).isEventProcessed(eventId);
    }

    @Test
    @DisplayName("Test de comportamiento del circuit breaker")
    void testCircuitBreakerDoesNotInterfere() {
        String eventId = "test-circuit-breaker-event";

        // Solo verificar que el circuit breaker no interfiere con operaciones normales
        when(processedEventRepository.isEventProcessed(eventId))
                .thenReturn(Mono.just(false));

        // Hacer una llamada normal
        StepVerifier.create(eventRepository.isEventProcessed(eventId))
                .expectNext(false)
                .verifyComplete();

        // Verificar que se hizo la llamada
        verify(processedEventRepository, times(1)).isEventProcessed(eventId);
    }

    @Test
    @DisplayName("Test diagnóstico: Verificar configuración de reintentos")
    void testRetryConfigurationDiagnostic() {
        String eventId = "diagnostic-test";

        // Mock que siempre falla
        when(processedEventRepository.isEventProcessed(eventId))
                .thenReturn(Mono.error(new RuntimeException("Database connection error")));

        // Ejecutar y capturar resultado
        StepVerifier.create(eventRepository.isEventProcessed(eventId))
                .expectError()
                .verify(Duration.ofSeconds(5));

        // Contar las invocaciones reales
        int actualInvocations = mockingDetails(processedEventRepository).getInvocations().size();
        System.out.println("=== DIAGNÓSTICO ===");
        System.out.println("Invocaciones totales realizadas: " + actualInvocations);
        System.out.println("Configuración esperada desde application-integration-test.yml:");
        System.out.println("- max-attempts: 3 (1 inicial + 2 reintentos)");
        System.out.println("- wait-duration: 50ms");

        // Verificar que se hicieron exactamente 3 llamadas
        verify(processedEventRepository, times(3)).isEventProcessed(eventId);
    }
}