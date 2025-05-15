package com.example.order.model;

public enum SagaStepType {
    CREATE_ORDER("createOrder"),
    RESERVE_STOCK("reserveStock"),
    PROCESS_PAYMENT("processPayment"),
    PROCESS_ORDER("processOrder"),
    UPDATE_INVENTORY("updateInventory"),
    NOTIFY_SHIPMENT("notifyShipment"),
    COMPLETE_ORDER("completeOrder"),
    FAILED_ORDER("failedOrder"),
    FAILED_EVENT("failedEvent"),
    GENERIC_STEP("genericStep"),
    UNKNOWN("unknown");


    private final String value;

    SagaStepType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SagaStepType fromValue(String value) {
        if (value == null) {
            return UNKNOWN;
        }

        for (SagaStepType stepType : SagaStepType.values()) {
            if (stepType.value.equals(value)) {
                return stepType;
            }
        }
        return UNKNOWN;
    }

    @Override
    public String toString() {
        return value;
    }
}