package com.example.order.config;

import com.example.order.domain.OrderStateMachine;
import com.example.order.repository.EventRepository;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.*;
import com.example.order.service.v2.SagaOrchestratorAtLeastOnceImplV2;
import com.example.order.service.v2.SagaOrchestratorAtMostOnceImplV2;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.reactive.TransactionalOperator;

@Slf4j
@Configuration
@EnableScheduling
public class SagaConfiguration {

    /**
     * ✅ MIGRACIÓN: Bean para OrderStateMachineService (híbrido)
     */
    @Bean
    public OrderStateMachineService orderStateMachineService() {
        log.info("Creating OrderStateMachineService bean");
        return new OrderStateMachineService();
    }

    /**
     * ✅ MIGRACIÓN: Mantener OrderStateMachine para compatibilidad (DEPRECATED)
     * @deprecated Use OrderStateMachineService instead
     */
    @Bean
    @Deprecated
    public OrderStateMachine orderStateMachine() {
        log.warn("OrderStateMachine bean is deprecated, use OrderStateMachineService instead");
        return OrderStateMachine.getInstance();
    }

    @Bean
    @Primary
    public SagaOrchestrator atMostOnceSagaOrchestrator(
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            @Qualifier("orderEventPublisher") EventPublisher eventPublisher,
            InventoryService inventoryService,
            CompensationManager compensationManager,
            EventRepository eventRepository,
            OrderStateMachineService orderStateMachineService) { // ✅ Cambiar a OrderStateMachineService

        return new SagaOrchestratorAtMostOnceImplV2(
                transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                inventoryService,
                compensationManager,
                eventRepository,
                orderStateMachineService // ✅ Usar OrderStateMachineService
        );
    }

    @Bean
    public SagaOrchestrator atLeastOnceSagaOrchestrator(
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            @Qualifier("orderEventPublisher") EventPublisher eventPublisher,
            InventoryService inventoryService,
            CompensationManager compensationManager,
            EventRepository eventRepository,
            OrderStateMachineService orderStateMachineService) { // ✅ Cambiar a OrderStateMachineService

        return new SagaOrchestratorAtLeastOnceImplV2(
                transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                inventoryService,
                compensationManager,
                eventRepository,
                orderStateMachineService // ✅ Usar OrderStateMachineService
        );
    }

    /**
     * ✅ MIGRACIÓN: Tarea programada para limpiar máquinas de estado en estados terminales
     * Se ejecuta cada 30 minutos
     */
    @Scheduled(fixedRate = 1800000) // 30 minutos
    public void cleanupTerminalStateMachines() {
        try {
            OrderStateMachineService service = orderStateMachineService();
            int countBefore = service.getActiveStateMachineCount();

            service.cleanupTerminalStates();

            int countAfter = service.getActiveStateMachineCount();
            int cleaned = countBefore - countAfter;

            if (cleaned > 0) {
                log.info("Cleaned up {} terminal state machines. Active count: {} -> {}",
                        cleaned, countBefore, countAfter);
            } else {
                log.debug("No terminal state machines to clean up. Active count: {}", countAfter);
            }
        } catch (Exception e) {
            log.error("Error during state machine cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * ✅ MIGRACIÓN: Métricas para monitorear el estado de las máquinas
     */
    @Scheduled(fixedRate = 300000) // 5 minutos
    public void reportStateMachineMetrics() {
        try {
            OrderStateMachineService service = orderStateMachineService();
            int activeCount = service.getActiveStateMachineCount();

            // Aquí podrías reportar métricas adicionales si tienes MeterRegistry disponible
            log.debug("Active state machines: {}", activeCount);

            // Alertar si hay demasiadas máquinas activas (posible memory leak)
            if (activeCount > 1000) {
                log.warn("High number of active state machines detected: {}. Consider investigating for memory leaks.", activeCount);
            }
        } catch (Exception e) {
            log.error("Error reporting state machine metrics: {}", e.getMessage(), e);
        }
    }
}