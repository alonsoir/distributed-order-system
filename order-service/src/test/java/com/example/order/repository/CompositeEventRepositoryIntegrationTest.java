package com.example.order.repository;

import com.example.order.repository.events.EventHistoryRepository;
import com.example.order.repository.events.ProcessedEventRepository;
import com.example.order.repository.orders.OrderRepository;
import com.example.order.repository.saga.SagaFailureRepository;
import com.example.order.repository.transactions.TransactionLockRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@Testcontainers
@SpringBootTest
@ActiveProfiles("unit")
public class CompositeEventRepositoryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CompositeEventRepositoryIntegrationTest.class);

    @Container
    static MySQLContainer<?> mySqlContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withExposedPorts(6379)
            .withReuse(true);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () ->
                String.format("r2dbc:mysql://%s:%d/%s",
                        mySqlContainer.getHost(),
                        mySqlContainer.getFirstMappedPort(),
                        mySqlContainer.getDatabaseName()));
        registry.add("spring.r2dbc.username", mySqlContainer::getUsername);
        registry.add("spring.r2dbc.password", mySqlContainer::getPassword);

        registry.add("spring.redis.host", redisContainer::getHost);
        registry.add("spring.redis.port", redisContainer::getFirstMappedPort);

        registry.add("spring.cloud.config.enabled", () -> "false");
    }

    @Autowired
    private FailureSimulator failureSimulator;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private R2dbcEntityTemplate r2dbcTemplate;

    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    private CompositeEventRepository compositeEventRepository;

    @BeforeEach
    void setUp() {
        // Limpiar tablas y datos de Redis antes de cada prueba
        Mono.when(
                r2dbcTemplate.getDatabaseClient().sql("DELETE FROM processed_events").then(),
                r2dbcTemplate.getDatabaseClient().sql("DELETE FROM transaction_locks").then(),
                redisTemplate.delete(redisTemplate.keys("*"))
        ).block(Duration.ofSeconds(5));

        // Resetear el simulador de fallos
        failureSimulator.reset();

        // Crear instancias de MeterRegistry y CircuitBreakerRegistry para el test
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();

        // Mock de otros repositorios para el test
        OrderRepository orderRepository = mock(OrderRepository.class);
        SagaFailureRepository sagaFailureRepository = mock(SagaFailureRepository.class);
        EventHistoryRepository eventHistoryRepository = mock(EventHistoryRepository.class);
        TransactionLockRepository transactionLockRepository = mock(TransactionLockRepository.class);

        // Disable internal retries for ProcessedEventRepository during tests
        processedEventRepository.setInternalRetryEnabled(false);

        // Inicializar el repositorio compuesto con todos los parámetros requeridos
        compositeEventRepository = new CompositeEventRepository(
                meterRegistry,                      // Parámetro 1: MeterRegistry
                circuitBreakerRegistry,             // Parámetro 2: CircuitBreakerRegistry
                processedEventRepository,           // Parámetro 3: ProcessedEventRepository
                orderRepository,                    // Parámetro 4: OrderRepository
                sagaFailureRepository,              // Parámetro 5: SagaFailureRepository
                eventHistoryRepository,             // Parámetro 6: EventHistoryRepository
                transactionLockRepository           // Parámetro 7: TransactionLockRepository
        );
    }

    @Test
    @DisplayName("Integración: Debe reintentar operaciones tras fallos transitorios en isEventProcessed")
    void testRetryOnTransientFailuresForIsEventProcessed() {
        // Configurar evento único para esta prueba
        String eventId = "test-retry-" + UUID.randomUUID();

        // Configurar el simulador para fallar las primeras 2 llamadas
        failureSimulator.setFailurePattern(eventId, 2);

        // Marcar el evento como no procesado primero
        compositeEventRepository.markEventAsProcessed(eventId)
                .block(Duration.ofSeconds(5));

        // Verificar que después de los reintentos, la operación tiene éxito
        StepVerifier.create(compositeEventRepository.isEventProcessed(eventId))
                .expectNext(true)
                .verifyComplete();

        // Verificar el número correcto de intentos
        assertEquals(3, failureSimulator.getCallCount(eventId),
                "Deberían haberse realizado 3 llamadas (1 original + 2 reintentos)");
    }

    @Test
    @DisplayName("Integración: Debe fallar después del número máximo de reintentos")
    void testFailAfterMaxRetries() {
        String eventId = "test-max-retries-" + UUID.randomUUID();

        // Configurar el simulador para fallar más veces que el número máximo de reintentos
        failureSimulator.setFailurePattern(eventId, 5); // Siempre fallará (más que MAX_RETRIES=3)

        // Verificar que después de los reintentos máximos, la operación falla
        StepVerifier.create(compositeEventRepository.isEventProcessed(eventId))
                .expectErrorMatches(e -> e instanceof RuntimeException &&
                        e.getMessage().contains("Database connection error"))
                .verify(Duration.ofSeconds(10));

        // Verificar que se hicieron exactamente 1+MAX_RETRIES intentos
        assertEquals(4, failureSimulator.getCallCount(eventId),
                "Deberían haberse realizado 4 llamadas (1 original + 3 reintentos)");
    }

    @Test
    @DisplayName("Integración: No debe reintentar errores no transitorios")
    void testNoRetryForNonTransientErrors() {
        String eventId = "test-non-transient-" + UUID.randomUUID();

        // Configurar el simulador para generar un error no transitorio
        failureSimulator.setNonTransientError(eventId);

        // Verificar que no hay reintentos para errores no transitorios
        StepVerifier.create(compositeEventRepository.isEventProcessed(eventId))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException)
                .verify(Duration.ofSeconds(5));

        // Verificar que solo se hizo una llamada (sin reintentos)
        assertEquals(1, failureSimulator.getCallCount(eventId),
                "Debería haberse realizado solo 1 llamada sin reintentos");
    }

    @Test
    @DisplayName("Integración: No debe reintentar operaciones exitosas")
    void testNoRetryForSuccessfulOperations() {
        String eventId = "test-success-" + UUID.randomUUID();

        // No configurar ningún patrón de fallo para este evento

        // Marcar el evento como procesado primero
        compositeEventRepository.markEventAsProcessed(eventId)
                .block(Duration.ofSeconds(5));

        // Verificar que la operación tiene éxito sin reintentos
        StepVerifier.create(compositeEventRepository.isEventProcessed(eventId))
                .expectNext(true)
                .verifyComplete();

        // Verificar que solo se hizo una llamada (sin reintentos)
        assertEquals(1, failureSimulator.getCallCount(eventId),
                "Debería haberse realizado solo 1 llamada sin reintentos");
    }

    @Test
    @DisplayName("Integración: Debe reintentar operaciones tras fallos transitorios en markEventAsProcessed")
    void testRetryOnTransientFailuresForMarkEventAsProcessed() {
        String eventId = "test-mark-retry-" + UUID.randomUUID();

        // Configurar el simulador para fallar las primeras 2 llamadas
        failureSimulator.setFailurePattern(eventId, 2);

        // Verificar que después de los reintentos, la operación tiene éxito
        StepVerifier.create(compositeEventRepository.markEventAsProcessed(eventId))
                .verifyComplete();

        // Verificar que el evento está realmente marcado como procesado
        failureSimulator.reset(); // Resetear para evitar fallos en la verificación
        StepVerifier.create(compositeEventRepository.isEventProcessed(eventId))
                .expectNext(true)
                .verifyComplete();

        // Verificar el número correcto de intentos
        assertEquals(3, failureSimulator.getCallCount(eventId),
                "Deberían haberse realizado 3 llamadas (1 original + 2 reintentos)");
    }

    /**
     * Configuración del test que registra nuestros beans de prueba
     */
    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        public FailureSimulator failureSimulator() {
            return new FailureSimulator();
        }

        /**
         * Bean para MeterRegistry en los tests
         */
        @Bean
        public MeterRegistry testMeterRegistry() {
            return new SimpleMeterRegistry();
        }

        /**
         * Bean para CircuitBreakerRegistry en los tests
         */
        @Bean
        public CircuitBreakerRegistry testCircuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }
    }

    /**
     * Aspecto que simula fallos en las operaciones para probar la lógica de resiliencia
     */
    @Aspect
    public static class FailureSimulator {
        private static final Logger log = LoggerFactory.getLogger(FailureSimulator.class);

        private final Map<String, Integer> failurePatterns = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();
        private final Map<String, Boolean> nonTransientErrors = new ConcurrentHashMap<>();

        public void setFailurePattern(String eventId, int failuresBeforeSuccess) {
            failurePatterns.put(eventId, failuresBeforeSuccess);
            callCounts.put(eventId, new AtomicInteger(0));
        }

        public void setNonTransientError(String eventId) {
            nonTransientErrors.put(eventId, true);
            callCounts.put(eventId, new AtomicInteger(0));
        }

        public int getCallCount(String eventId) {
            return callCounts.getOrDefault(eventId, new AtomicInteger(0)).get();
        }

        public void reset() {
            failurePatterns.clear();
            callCounts.clear();
            nonTransientErrors.clear();
        }

        @Around("execution(* com.example.order.repository.events.ProcessedEventRepository.isEventProcessed(..)) || " +
                "execution(* com.example.order.repository.events.ProcessedEventRepository.markEventAsProcessed(..))")
        public Object simulateFailure(ProceedingJoinPoint joinPoint) throws Throwable {
            Object[] args = joinPoint.getArgs();
            if (args.length > 0 && args[0] instanceof String) {
                String eventId = (String) args[0];

                // Incrementar el contador de llamadas para este evento
                AtomicInteger counter = callCounts.computeIfAbsent(eventId, k -> new AtomicInteger(0));
                int callNumber = counter.incrementAndGet();

                log.info("Llamada #{} a {} con eventId={}", callNumber,
                        joinPoint.getSignature().getName(), eventId);

                // Comprobar si debemos generar un error no transitorio
                if (nonTransientErrors.containsKey(eventId)) {
                    log.info("Simulando error NO transitorio para eventId={}", eventId);
                    return Mono.error(new IllegalArgumentException("Invalid parameter"));
                }

                // Comprobar si debemos generar un error transitorio
                if (failurePatterns.containsKey(eventId)) {
                    int failuresNeeded = failurePatterns.get(eventId);

                    if (callNumber <= failuresNeeded) {
                        log.info("Simulando error transitorio #{} para eventId={}", callNumber, eventId);
                        return Mono.error(new RuntimeException("Database connection error"));
                    }
                }
            }

            // Si no hay que simular errores, ejecutar normalmente
            return joinPoint.proceed();
        }
    }
}