package com.example.order.config;

import com.example.order.repository.orders.OrderRepository; // Added import
import com.example.order.service.DynamicOrderService;
import com.example.order.service.OrderService;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@TestConfiguration
public class TestStrategyConfiguration {

    /**
     * Bean mock de OrderService para tests de integración
     * Se marca como @Primary para que Spring lo use por defecto en lugar de las 3 implementaciones reales
     */
    @Bean
    // @Primary // Removed to resolve conflict, testDynamicOrderService will be the primary OrderService mock
    @Profile("integration-test")
    public OrderService testOrderService() {
        return Mockito.mock(OrderService.class);
    }

    @Bean
    @Primary // This will be the primary mock for OrderService via DynamicOrderService
    @Profile("integration-test")
    public DynamicOrderService testDynamicOrderService() {
        return Mockito.mock(DynamicOrderService.class);
    }

    @Bean
    @Primary
    @Profile("integration-test")
    public OrderRepository mockOrderRepository() {
        return Mockito.mock(OrderRepository.class);
    }
}