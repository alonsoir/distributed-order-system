package com.example.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceUnitTest {

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private OrderService orderService;

    @Test
    void shouldReserveStockSuccessfully() {
        Long orderId = 1L;
        int quantity = 10;

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.empty());

        Mono<Void> result = inventoryService.reserveStock(orderId, quantity);

        StepVerifier.create(result)
                .verifyComplete();

        verify(inventoryService).reserveStock(orderId, quantity);
    }

    @Test
    void shouldReleaseStockOnFailure() {
        Long orderId = 1L;
        int quantity = 10;
        RuntimeException error = new RuntimeException("Stock reservation failed");

        when(inventoryService.reserveStock(orderId, quantity)).thenReturn(Mono.error(error));
        when(inventoryService.releaseStock(orderId, quantity)).thenReturn(Mono.empty());

        Mono<Void> result = inventoryService.reserveStock(orderId, quantity);

        StepVerifier.create(result)
                .expectErrorMatches(e -> e.getMessage().equals("Stock reservation failed"))
                .verify();

        verify(inventoryService).reserveStock(orderId, quantity);
        verify(inventoryService).releaseStock(orderId, quantity);
    }
}