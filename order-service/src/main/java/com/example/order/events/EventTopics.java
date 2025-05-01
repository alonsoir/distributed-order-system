package com.example.order.events;

public enum EventTopics {
    ORDER_CREATED("order-created"),
    STOCK_RESERVED("stock-reserved"),
    ORDER_FAILED("order-failed");

    private final String topic;

    EventTopics(String topic) {
        this.topic = topic;
    }

    public String getTopic() {
        return topic;
    }
}