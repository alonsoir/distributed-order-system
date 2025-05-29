package com.example.order.service;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Primary  // ← AÑADIR ESTA LÍNEA
public class DynamicOrderService extends AbstractOrderService implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(DynamicOrderService.class);

    private final Map<String, SagaOrchestrator> orchestratorStrategies;

    // Usamos AtomicReference para garantizar thread-safety
    private final AtomicReference<String> defaultStrategyRef = new AtomicReference<>("atLeastOnce");

    public DynamicOrderService(
            CircuitBreakerRegistry circuitBreakerRegistry,
            MeterRegistry meterRegistry,
            @Qualifier("atLeastOnce") SagaOrchestrator atLeastOnceOrchestrator,
            @Qualifier("sagaOrchestratorImpl2") SagaOrchestrator atMostOnceOrchestrator) {
        super(circuitBreakerRegistry, meterRegistry);

        // Inicializar mapa de estrategias
        this.orchestratorStrategies = new ConcurrentHashMap<>();  // Thread-safe map
        this.orchestratorStrategies.put("atLeastOnce", atLeastOnceOrchestrator);
        this.orchestratorStrategies.put("atMostOnce", atMostOnceOrchestrator);

        // Inicializar métricas
        meterRegistry.gauge("order.service.strategy.active",
                Tags.of("strategy", "atLeastOnce"),
                this,
                service -> service.getDefaultStrategy().equals("atLeastOnce") ? 1 : 0);

        meterRegistry.gauge("order.service.strategy.active",
                Tags.of("strategy", "atMostOnce"),
                this,
                service -> service.getDefaultStrategy().equals("atMostOnce") ? 1 : 0);
    }

    /**
     * Obtiene la estrategia actual
     */
    public String getDefaultStrategy() {
        return defaultStrategyRef.get();
    }

    /**
     * Permite cambiar la estrategia en tiempo de ejecución
     * @param strategyName Nombre de la estrategia a usar
     * @return true si la estrategia se cambió correctamente, false si ya estaba configurada
     * @throws IllegalArgumentException si la estrategia no existe
     */
    public boolean setDefaultStrategy(String strategyName) {
        if (!orchestratorStrategies.containsKey(strategyName)) {
            throw new IllegalArgumentException("Strategy not found: " + strategyName);
        }

        String oldStrategy = defaultStrategyRef.getAndSet(strategyName);
        boolean changed = !oldStrategy.equals(strategyName);

        if (changed) {
            log.info("Saga orchestration strategy changed from {} to {}", oldStrategy, strategyName);
            // Incrementar contador de cambios de estrategia
            meterRegistry.counter("order.service.strategy.changes",
                    "from", oldStrategy,
                    "to", strategyName).increment();
        }

        return changed;
    }

    /**
     * Registra una nueva estrategia en tiempo de ejecución
     */
    public void registerStrategy(String name, SagaOrchestrator orchestrator) {
        if (orchestrator == null) {
            throw new IllegalArgumentException("Orchestrator cannot be null");
        }

        SagaOrchestrator previous = orchestratorStrategies.putIfAbsent(name, orchestrator);
        if (previous == null) {
            log.info("New saga orchestration strategy registered: {}", name);
            // Añadir métrica para la nueva estrategia
            meterRegistry.gauge("order.service.strategy.active",
                    Tags.of("strategy", name),
                    this,
                    service -> service.getDefaultStrategy().equals(name) ? 1 : 0);
        }
    }

    /**
     * Devuelve las estrategias disponibles
     */
    public Set<String> getAvailableStrategies() {
        return Collections.unmodifiableSet(orchestratorStrategies.keySet());
    }

    /**
     * Procesa una orden usando la estrategia por defecto
     */
    @Override
    public Mono<Order> processOrder(String externalReference, int quantity, double amount) {
        return processOrderWithStrategy(externalReference, quantity, amount, getDefaultStrategy());
    }

    /**
     * Procesa una orden especificando la estrategia a usar
     */
    public Mono<Order> processOrderWithStrategy(
            String externalReference,
            int quantity,
            double amount,
            String strategyName) {

        if (!orchestratorStrategies.containsKey(strategyName)) {
            return Mono.error(new IllegalArgumentException("Unknown strategy: " + strategyName));
        }

        Timer.Sample timer = Timer.start(meterRegistry);

        log.debug("Processing order using {} strategy", strategyName);

        SagaOrchestrator sagaOrchestrator = orchestratorStrategies.get(strategyName);

        return validateOrderParams(externalReference, quantity, amount)
                .then(getCircuitBreaker("orderProcessing"))
                .flatMap(circuitBreaker ->
                        executeOrderSagaWithStrategy(quantity, amount, sagaOrchestrator)
                                .doOnSuccess(order -> {
                                    recordSuccess(circuitBreaker, order);
                                    // Registrar métrica de tiempo para esta estrategia
                                    timer.stop(meterRegistry.timer("order.service.processing.time",
                                            "strategy", strategyName,
                                            "outcome", "success"));
                                    // Incrementar contador de éxitos
                                    meterRegistry.counter("order.service.outcomes",
                                            "strategy", strategyName,
                                            "outcome", "success").increment();
                                })
                                .doOnError(e -> {
                                    recordError(circuitBreaker, e);
                                    // Registrar métrica de tiempo para esta estrategia
                                    timer.stop(meterRegistry.timer("order.service.processing.time",
                                            "strategy", strategyName,
                                            "outcome", "error"));
                                    // Incrementar contador de errores
                                    meterRegistry.counter("order.service.outcomes",
                                            "strategy", strategyName,
                                            "outcome", "error",
                                            "error_type", e.getClass().getSimpleName()).increment();
                                })
                                .onErrorResume(e -> {
                                    String reason = determineErrorReason(e);
                                    log.warn("Creating failed order due to: {}", reason);
                                    return createFailedOrderWithStrategy(reason, externalReference, sagaOrchestrator);
                                })
                )
                .onErrorResume(e -> {
                    // Handle the case where getCircuitBreaker throws an error
                    if (e instanceof RuntimeException && "Circuit breaker open".equals(e.getMessage())) {
                        return createFailedOrderWithStrategy("circuit_breaker_open", externalReference,
                                orchestratorStrategies.get(strategyName));
                    }
                    return Mono.error(e);
                });
    }

    private Mono<Order> executeOrderSagaWithStrategy(int quantity, double amount, SagaOrchestrator sagaOrchestrator) {
        return sagaOrchestrator.executeOrderSaga(quantity, amount);
    }

    private Mono<Order> createFailedOrderWithStrategy(String reason, String externalReference, SagaOrchestrator sagaOrchestrator) {
        log.warn("Creating failed order for externalReference {} with reason: {}", externalReference, reason);

        // Create the failed order immediately, then trigger the event creation asynchronously
        Order failedOrder = new Order(null, "failed", externalReference);

        // Return the order immediately, but also trigger the event creation
        return Mono.just(failedOrder)
                .doOnSubscribe(s ->
                        // Fire and forget the event creation - don't wait for it to complete
                        sagaOrchestrator.createFailedEvent(reason, externalReference)
                                .subscribe(
                                        success -> log.debug("Failed event created successfully for {}", externalReference),
                                        error -> log.error("Error creating failed event: {}", error.getMessage(), error)
                                )
                );
    }

    @Override
    protected Mono<Order> executeOrderSaga(int quantity, double amount) {
        // Usa el orquestrador por defecto (determinado dinámicamente)
        return orchestratorStrategies.get(getDefaultStrategy()).executeOrderSaga(quantity, amount);
    }

    @Override
    protected Mono<Void> createFailedEvent(String reason, String externalReference) {
        // Usa el orquestrador por defecto (determinado dinámicamente)
        return orchestratorStrategies.get(getDefaultStrategy()).createFailedEvent(reason, externalReference);
    }
}