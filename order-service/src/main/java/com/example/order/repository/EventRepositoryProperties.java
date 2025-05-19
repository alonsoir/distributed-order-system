package com.example.order.repository;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Propiedades configurables para el EventRepository.
 * Permite ajustar valores como tiempos de espera, reintentos, etc.
 * desde la configuración del aplicativo.
 */
@ConfigurationProperties(prefix = "event.repository")
@Configuration
public class EventRepositoryProperties {

    /**
     * Número máximo de reintentos para operaciones transitorias
     */
    private int maxRetries = 3;

    /**
     * Tiempo de espera inicial entre reintentos (en milisegundos)
     */
    private int retryBackoffMillis = 100;

    /**
     * Tiempo máximo de espera para operaciones (en milisegundos)
     */
    private int operationTimeoutMillis = 5000;

    /**
     * Habilitar/deshabilitar el circuit breaker
     */
    private boolean enableCircuitBreaker = true;

    /**
     * Umbral de fallos para abrir el circuit breaker
     */
    private int circuitBreakerFailureThreshold = 5;

    /**
     * Tiempo en estado abierto para el circuit breaker (en milisegundos)
     */
    private int circuitBreakerOpenTimeMillis = 30000;

    /**
     * Registro detallado de operaciones (además de errores)
     */
    private boolean verboseLogging = false;

    /**
     * Prefijo para métricas
     */
    private String metricsPrefix = "repository";

    // Getters y setters

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public void setRetryBackoffMillis(int retryBackoffMillis) {
        this.retryBackoffMillis = retryBackoffMillis;
    }

    public int getOperationTimeoutMillis() {
        return operationTimeoutMillis;
    }

    public void setOperationTimeoutMillis(int operationTimeoutMillis) {
        this.operationTimeoutMillis = operationTimeoutMillis;
    }

    public boolean isEnableCircuitBreaker() {
        return enableCircuitBreaker;
    }

    public void setEnableCircuitBreaker(boolean enableCircuitBreaker) {
        this.enableCircuitBreaker = enableCircuitBreaker;
    }

    public int getCircuitBreakerFailureThreshold() {
        return circuitBreakerFailureThreshold;
    }

    public void setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold) {
        this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
    }

    public int getCircuitBreakerOpenTimeMillis() {
        return circuitBreakerOpenTimeMillis;
    }

    public void setCircuitBreakerOpenTimeMillis(int circuitBreakerOpenTimeMillis) {
        this.circuitBreakerOpenTimeMillis = circuitBreakerOpenTimeMillis;
    }

    public boolean isVerboseLogging() {
        return verboseLogging;
    }

    public void setVerboseLogging(boolean verboseLogging) {
        this.verboseLogging = verboseLogging;
    }

    public String getMetricsPrefix() {
        return metricsPrefix;
    }

    public void setMetricsPrefix(String metricsPrefix) {
        this.metricsPrefix = metricsPrefix;
    }
}