package com.example.order.model;

import lombok.Data;

@Data
public class Order {
    private Long id;
    private String status;
    private String correlationId;

    public Order(Long id, String status, String correlationId) {
        this.id = id;
        this.status = status;
        this.correlationId = correlationId;
    }
}