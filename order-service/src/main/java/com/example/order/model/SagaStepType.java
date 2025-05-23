package com.example.order.model;

/**
 * Enumeración que define los tipos de pasos en una saga
 */
public enum SagaStepType {
    // Pasos de proceso principal
    CREATE_ORDER("CREATE_ORDER"),
    RESERVE_STOCK("RESERVE_STOCK"),
    PROCESS_PAYMENT("PROCESS_PAYMENT"),
    PROCESS_ORDER("PROCESS_ORDER"),
    PREPARE_ORDER("PREPARE_ORDER"),
    SHIP_ORDER("SHIP_ORDER"),
    DELIVER_ORDER("DELIVER_ORDER"),

    // Pasos de compensación
    CANCEL_ORDER("CANCEL_ORDER"),
    RELEASE_STOCK("RELEASE_STOCK"),
    REFUND_PAYMENT("REFUND_PAYMENT"),

    // Pasos de manejo de errores
    FAILED_EVENT("FAILED_EVENT"),
    FAILED_ORDER("FAILED_ORDER"),
    TECHNICAL_EXCEPTION("TECHNICAL_EXCEPTION"),

    // Pasos genéricos
    GENERIC_STEP("GENERIC_STEP"),
    NOTIFICATION("NOTIFICATION"),
    AUDIT("AUDIT"),
    UNKNOWN("UNKNOWN");

    private final String value;

    SagaStepType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SagaStepType fromValue(String value) {
        for (SagaStepType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid saga step type: " + value);
    }

    /**
     * Verifica si el tipo de paso es de compensación
     */
    public boolean isCompensationStep() {
        return this == CANCEL_ORDER ||
                this == RELEASE_STOCK ||
                this == REFUND_PAYMENT;
    }

    /**
     * Verifica si el tipo de paso es de manejo de errores
     */
    public boolean isErrorHandlingStep() {
        return this == FAILED_EVENT ||
                this == FAILED_ORDER ||
                this == TECHNICAL_EXCEPTION;
    }

    /**
     * Verifica si el tipo de paso es del flujo principal
     */
    public boolean isMainFlowStep() {
        return this == CREATE_ORDER ||
                this == RESERVE_STOCK ||
                this == PROCESS_PAYMENT ||
                this == PROCESS_ORDER ||
                this == PREPARE_ORDER ||
                this == SHIP_ORDER ||
                this == DELIVER_ORDER;
    }
}