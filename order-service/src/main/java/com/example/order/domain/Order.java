package com.example.order.domain;

/**
 * Clase que representa una orden en el sistema.
 */
public class Order {
    private  Long id;
    private  String status;
    private  String correlationId;

    public Order() {
    }
    public Order(Long id, String status, String correlationId) {
        this.id = id;
        this.status = status;
        this.correlationId = correlationId;
    }

    // Getters tradicionales
    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    // Métodos de estilo record para compatibilidad con los tests
    public Long id() {
        return id;
    }

    public String status() {
        return status;
    }

    public String correlationId() {
        return correlationId;
    }

    @Override
    public String toString() {
        return "Order{" +
                "id=" + id +
                ", status='" + status + '\'' +
                ", correlationId='" + correlationId + '\'' +
                '}';
    }

    public void setId(long l) {
        this.id = l;
    }

    public void setStatus(String completed) {
        this.status = completed;
    }
}