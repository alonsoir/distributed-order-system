package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.events.OrderEvent;
import com.example.order.events.OrderFailedEvent;
import com.example.order.model.SagaStep;
import reactor.core.publisher.Mono;
/*
Esta interfaz representa el contrato de negocio del orquestador de sagas, para ambos orquestadores.
Uno representa el caso de uso AT LEAST ONCE, y el otro representa el caso de uso AT MOST ONCE.
Falta renombrar los nombres de las clases de negocio, porque son muy genericos.
* */
public interface SagaOrchestrator {
    // No le doy mucha importancia a esto, es solo para que se entienda el concepto de transaccion distribuida.
    // No es una practica real, pero es para que se entienda el concepto de transaccion distribuida.
    // En la transaccion distribuida, se debe tener en cuenta el rollback de la transaccion, y es en lo que nos vamos a
    // centrar, no en un caso particular de una tienda de conveniencia Temu o algo así.
    Mono<Order> executeOrderSaga(int quantity, double amount);

    // tengo que crear una orden aunque sea con una cantidad. Esto es un ejemplo, no un caso real, pero es para que
    // se entienda el concepto
    Mono<Order> createOrder(Long orderId, String correlationId, String eventId, String externalReference, int quantity);

    Mono<OrderEvent> executeStep(SagaStep step);
    Mono<Void> publishFailedEvent(OrderFailedEvent event);
    Mono<OrderEvent> publishEvent(OrderEvent event, String step, String topic);
    Mono<Void> createFailedEvent(String reason, String externalReference);
}