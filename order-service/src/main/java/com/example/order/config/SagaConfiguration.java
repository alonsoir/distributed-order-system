package com.example.order.config;

import com.example.order.repository.EventRepository;
import com.example.order.resilience.ResilienceManager;
import com.example.order.service.*;
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
    @Qualifier("sagaOrchestratorImpl2")
    public SagaOrchestrator sagaOrchestratorImpl2(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            @Qualifier("orderEventPublisher") EventPublisher eventPublisher,
            InventoryService inventoryService,
            CompensationManager compensationManager,
            EventRepository eventRepository) {
        /*
        public SagaOrchestratorAtMostOnceImpl2(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            @Qualifier("orderEventPublisher") EventPublisher eventPublisher,
            InventoryService inventoryService,
            CompensationManager compensationManager,
            EventRepository eventRepository)
        * */
        return new SagaOrchestratorAtMostOnceImpl2(
                databaseClient,
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
    @Qualifier("legacySagaOrchestrator")
    public SagaOrchestrator legacySagaOrchestrator(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            MeterRegistry meterRegistry,
            IdGenerator idGenerator,
            ResilienceManager resilienceManager,
            @Qualifier("orderEventPublisher") EventPublisher eventPublisher,
            InventoryService inventoryService,
            CompensationManager compensationManager,
            EventRepository eventRepository) {
        return new SagaOrchestratorAtLeastOnceImpl(
                databaseClient,
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
