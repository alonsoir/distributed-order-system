package com.example.order.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.mock;

@TestConfiguration
public class TestConfig {
    @Bean
    public InventoryService inventoryService() {
        return mock(InventoryService.class);
    }
}