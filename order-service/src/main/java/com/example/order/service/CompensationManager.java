package com.example.order.service;

import com.example.order.model.SagaStep;
import reactor.core.publisher.Mono;

/**
 * Interface que define las operaciones para la gestión de compensaciones en transacciones saga.
 */
public interface CompensationManager {

    /**
     * Ejecuta la compensación asociada a un paso del saga.
     *
     * @param step El paso del saga que contiene la acción de compensación a ejecutar
     * @return Un Mono que completa cuando la compensación se ha ejecutado con éxito
     * @throws IllegalArgumentException si el paso o su compensación son nulos
     */
    Mono<Void> executeCompensation(SagaStep step);
}