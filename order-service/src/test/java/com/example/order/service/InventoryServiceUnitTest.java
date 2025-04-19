package com.example.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceUnitTest {

    @Mock
    private InventoryService inventoryService;

    @Test
    void shouldReserveStockSuccessfully() {
        // Arrange
        Long orderId = 1L;
        int quantity = 10;

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        // Act
        Mono<Void> result = inventoryService.reserveStock(orderId, quantity);

        // Assert
        StepVerifier.create(result)
                .verifyComplete();

        verify(inventoryService, times(1)).reserveStock(orderId, quantity);
        verifyNoMoreInteractions(inventoryService);
    }

    @Test
    void shouldFailToReserveStockWithError() {
        // Arrange
        Long orderId = 1L;
        int quantity = 10;
        RuntimeException error = new RuntimeException("Stock reservation failed");

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.error(error));

        // Act
        Mono<Void> result = inventoryService.reserveStock(orderId, quantity);

        // Assert
        StepVerifier.create(result)
                .expectErrorMatches(e -> e.getMessage().equals("Stock reservation failed"))
                .verify();

        verify(inventoryService, times(1)).reserveStock(orderId, quantity);
        verifyNoMoreInteractions(inventoryService);
    }
}