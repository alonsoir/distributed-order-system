package com.example.order.config;

import com.example.order.repository.EventRepository;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.*;
import com.example.order.service.v2.SagaOrchestratorAtLeastOnceImplV2;
import com.example.order.service.v2.SagaOrchestratorAtMostOnceImplV2;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class SagaConfiguration {

    @Bean
    @Primary
    @Qualifier("atMostOnceSagaOrchestrator")
    public SagaOrchestrator atMostOnceSagaOrchestrator(
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            @Qualifier("orderEventPublisher") EventPublisher eventPublisher,
            InventoryService inventoryService,
            CompensationManager compensationManager,
            EventRepository eventRepository) {

        return new SagaOrchestratorAtMostOnceImplV2(
                transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                inventoryService,
                compensationManager,
                eventRepository
        );
    }

    @Bean
    @Qualifier("atLeastOnceSagaOrchestrator")
    public SagaOrchestrator atLeastOnceSagaOrchestrator(
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            @Qualifier("orderEventPublisher") EventPublisher eventPublisher,
            InventoryService inventoryService,
            CompensationManager compensationManager,
            EventRepository eventRepository) {
        return new SagaOrchestratorAtLeastOnceImplV2(
                transactionalOperator,
                meterRegistry,
                idGenerator,
                resilienceManager,
                eventPublisher,
                inventoryService,
                compensationManager,
                eventRepository
        );
    }
}
