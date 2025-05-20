package com.example.order.domain;

public class Order {
    private Long id;
    private OrderStatus status;
    private String correlationId;

    public Order() {
    }

    public Order(Long id, OrderStatus status, String correlationId) {
        this.id = id;
        this.status = status;
        this.correlationId = correlationId;
    }

    // Constructor adicional para mantener compatibilidad
    public Order(Long id, String statusValue, String correlationId) {
        this.id = id;
        this.status = OrderStatus.fromValue(statusValue);
        this.correlationId = correlationId;
    }

    // Getters y setters
    public Long getId() {
        return id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getStatusValue() {
        return status.getValue();
    }

    public String getCorrelationId() {
        return correlationId;
    }

    // Métodos de estilo record
    public Long id() {
        return id;
    }

    public OrderStatus status() {
        return status;
    }

    public String statusValue() {
        return status.getValue();
    }

    public String correlationId() {
        return correlationId;
    }

    public void setId(long id) {
        this.id = id;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    // Setter adicional para mantener compatibilidad
    public void setStatus(String statusValue) {
        this.status = OrderStatus.fromValue(statusValue);
    }

    @Override
    public String toString() {
        return "Order{" +
                "id=" + id +
                ", status='" + status + '\'' +
                ", correlationId='" + correlationId + '\'' +
                '}';
    }
}