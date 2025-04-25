package com.example.order.service;

public record CompensationTask(Long orderId, String correlationId, String errorMessage, int retries) {}