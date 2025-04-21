package com.example.order.events;

public record OrderCreatedEvent(
        String orderId,
        String correlationId,
        String eventId,
        String status
) {}
