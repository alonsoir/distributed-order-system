package com.example.order.config;

import com.example.order.repository.EventRepositoryProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.time.Duration;

/**
 * Configuración para EventRepository.
 * Activa propiedades y configura el circuit breaker.
 */
@Configuration
@EnableConfigurationProperties(EventRepositoryProperties.class)
public class EventRepositoryConfig {

    private static final Logger log = LoggerFactory.getLogger(EventRepositoryConfig.class);

    private final EventRepositoryProperties properties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    public EventRepositoryConfig(
            EventRepositoryProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.properties = properties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @PostConstruct
    public void initialize() {
        log.info("Initializing EventRepositoryConfig with: maxRetries={}, retryBackoff={}ms, operationTimeout={}ms",
                properties.getMaxRetries(),
                properties.getRetryBackoffMillis(),
                properties.getOperationTimeoutMillis());

        if (properties.isEnableCircuitBreaker()) {
            // Configurar Circuit Breaker personalizado para las operaciones del repositorio
            CircuitBreakerConfig repositoryCircuitBreakerConfig = CircuitBreakerConfig.custom()
                    .failureRateThreshold(50) // Porcentaje de fallos que dispara la apertura
                    .waitDurationInOpenState(Duration.ofMillis(properties.getCircuitBreakerOpenTimeMillis()))
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(properties.getCircuitBreakerFailureThreshold())
                    .permittedNumberOfCallsInHalfOpenState(2)
                    .automaticTransitionFromOpenToHalfOpenEnabled(true)
                    .build();

            // Registrar nuestro custom config con un prefijo específico
            for (String operation : getRepositoryOperations()) {
                String cbName = "event-repository-" + operation;
                try {
                    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(cbName, repositoryCircuitBreakerConfig);
                    log.debug("Registered circuit breaker: {} with custom config", cbName);
                } catch (Exception e) {
                    log.warn("Circuit breaker {} already exists, using existing configuration", cbName);
                }
            }

            log.info("Circuit breakers configured for EventRepository with threshold={}, openTime={}ms",
                    properties.getCircuitBreakerFailureThreshold(),
                    properties.getCircuitBreakerOpenTimeMillis());
        } else {
            log.info("Circuit breakers disabled for EventRepository");
        }

        log.info("EventRepositoryConfig initialized successfully");
    }

    private String[] getRepositoryOperations() {
        return new String[] {
                "isEventProcessed",
                "markEventAsProcessed",
                "checkAndMarkEventAsProcessed",
                "findOrderById",
                "saveOrderData",
                "updateOrderStatus",
                "insertStatusAuditLog",
                "insertCompensationLog",
                "recordStepFailure",
                "recordSagaFailure",
                "saveEventHistory",
                "acquireTransactionLock",
                "releaseTransactionLock"
        };
    }
}