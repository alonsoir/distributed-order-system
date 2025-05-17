package com.example.order.config;

import com.example.order.repository.CompositeEventRepository;
import com.example.order.repository.EventRepository;
import com.example.order.repository.base.SecurityUtils;
import com.example.order.repository.base.ValidationUtils;
import com.example.order.repository.events.EventHistoryRepository;
import com.example.order.repository.events.ProcessedEventRepository;
import com.example.order.repository.orders.OrderRepository;
import com.example.order.repository.saga.SagaFailureRepository;
import com.example.order.repository.transactions.TransactionLockRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Configuración de los repositorios utilizados en la aplicación.
 * Esta clase proporciona las definiciones de todos los beans necesarios para
 * instanciar CompositeEventRepository y sus dependencias.
 */
@Configuration
public class RepositoryConfig {

    /*
     * TODO: Refactorizar la dependencia circular entre OrderRepository y ProcessedEventRepository
     *
     * El diseño actual presenta una dependencia circular:
     * - OrderRepository depende de ProcessedEventRepository
     * - ProcessedEventRepository se comunica indirectamente con OrderRepository
     *
     * Posibles soluciones:
     * 1. Extraer la funcionalidad común a una tercera clase:
     *    - Crear un servicio EventProcessingService que contenga la lógica compartida
     *    - Ambos repositorios dependerían de este servicio, no entre sí
     *
     * 2. Utilizar el patrón mediador:
     *    - Crear un EventMediator que coordine la comunicación entre repositorios
     *    - Los repositorios publicarían eventos en el mediador y se suscribirían a eventos relevantes
     *
     * 3. Revisión de responsabilidades:
     *    - Analizar si la verificación de idempotencia debe estar en OrderRepository o si debería
     *      ser responsabilidad exclusiva de ProcessedEventRepository
     *    - Considerar mover la lógica de checkAndMarkEventAsProcessed a un nivel superior (servicio)
     *
     * 4. Usar eventos de dominio:
     *    - Implementar un mecanismo de eventos de dominio donde OrderRepository publique eventos
     *    - ProcessedEventRepository actuaría como un listener de estos eventos
     *
     * La solución ideal dependerá del análisis detallado del flujo de datos y las responsabilidades
     * de cada componente. Por ahora, usamos @Lazy como solución temporal.
     */

    /**
     * Bean para ValidationUtils que se inyectará en los repositorios
     */
    @Bean
    public ValidationUtils validationUtils() {
        return new ValidationUtils();
    }

    /**
     * Bean para SecurityUtils que se inyectará en los repositorios
     */
    @Bean
    public SecurityUtils securityUtils() {
        return new SecurityUtils();
    }

    /**
     * ProcessedEventRepository - Repositorio para eventos procesados
     */
    @Bean
    public ProcessedEventRepository processedEventRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils) {
        return new ProcessedEventRepository(
                databaseClient,
                transactionalOperator,
                securityUtils,
                validationUtils);
    }

    /**
     * OrderRepository - Repositorio para órdenes
     * Utiliza @Lazy en la inyección de ProcessedEventRepository para romper la dependencia circular
     */
    @Bean
    public OrderRepository orderRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils,
            @Lazy ProcessedEventRepository processedEventRepository) {  // @Lazy es una solución temporal para la dependencia circular
        return new OrderRepository(
                databaseClient,
                transactionalOperator,
                securityUtils,
                validationUtils,
                processedEventRepository);
    }

    /**
     * SagaFailureRepository - Repositorio para fallos de saga
     */
    @Bean
    public SagaFailureRepository sagaFailureRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils) {
        return new SagaFailureRepository(
                databaseClient,
                transactionalOperator,
                securityUtils,
                validationUtils);
    }

    /**
     * EventHistoryRepository - Repositorio para historial de eventos
     */
    @Bean
    public EventHistoryRepository eventHistoryRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils) {
        return new EventHistoryRepository(
                databaseClient,
                transactionalOperator,
                securityUtils,
                validationUtils);
    }

    /**
     * TransactionLockRepository - Repositorio para bloqueos de transacciones
     */
    @Bean
    public TransactionLockRepository transactionLockRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transactionalOperator,
            SecurityUtils securityUtils,
            ValidationUtils validationUtils) {
        return new TransactionLockRepository(
                databaseClient,
                transactionalOperator,
                securityUtils,
                validationUtils);
    }

    /**
     * Implementación principal de EventRepository que compone múltiples repositorios especializados
     */
    @Bean
    @Primary
    public EventRepository eventRepository(
            ProcessedEventRepository processedEventRepository,
            OrderRepository orderRepository,
            SagaFailureRepository sagaFailureRepository,
            EventHistoryRepository eventHistoryRepository,
            TransactionLockRepository transactionLockRepository) {
        return new CompositeEventRepository(
                processedEventRepository,
                orderRepository,
                sagaFailureRepository,
                eventHistoryRepository,
                transactionLockRepository);
    }
}