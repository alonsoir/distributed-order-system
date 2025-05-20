package com.example.order.domain;

/**
 * Enum que representa los diferentes estados de una orden
 * Tanto para las tuplas de la bd como para los eventos.
 **/
public enum OrderStatus {
    ORDER_CREATED("ORDER_CREATED"),
    ORDER_PENDING("ORDER_PENDING"),
    STOCK_RESERVED("STOCK_RESERVED"),
    ORDER_COMPLETED("ORDER_COMPLETED"),
    ORDER_FAILED("ORDER_FAILED"),
    ORDER_UNKNOWN("ORDER_UNKNOWN"),
    ORDER_CANCELED("ORDER_CANCELED");
    private final String value;

    OrderStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static OrderStatus fromValue(String value) {
        for (OrderStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid order status: " + value);
    }
}