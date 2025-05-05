package com.example.order.service;

/**
 * Interfaz para generación de identificadores utilizados en el sistema.
 * Esta abstracción permite un mayor control en los tests y mejora la determinación.
 */
public interface IdGenerator {
    /**
     * Genera un ID de correlación único para seguimiento de transacciones.
     * @return Un ID de correlación como String.
     */
    String generateCorrelationId();
    String generateExternalReference();
    /**
     * Genera un ID de evento único para eventos del sistema.
     * @return Un ID de evento como Long.
     */
    String generateEventId();
    Long generateOrderId();
}
