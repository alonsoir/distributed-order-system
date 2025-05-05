package com.example.order.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementación mejorada del IdGenerator optimizada para sistemas de alta escala
 * y rendimiento.
 */
@Component
public class DefaultIdGenerator implements IdGenerator {

    private final AtomicLong orderIdCounter;
    private final ReactiveRedisTemplate<String, Long> redisTemplate;
    private final int nodeId;
    private static final String ORDER_ID_KEY = "order:id:counter";
    private static final int NODE_ID_BITS = 10; // Soporta hasta 1024 nodos
    private static final int SEQUENCE_BITS = 12; // Soporta hasta 4096 secuencias por ms

    @Autowired
    public DefaultIdGenerator(
            ReactiveRedisTemplate<String, Long> redisTemplate,
            @Value("${app.node-id:0}") int nodeId) {
        this.redisTemplate = redisTemplate;
        this.nodeId = nodeId % (1 << NODE_ID_BITS);
        this.orderIdCounter = new AtomicLong(ThreadLocalRandom.current().nextLong(1, 1000));
    }

    /**
     * Genera un UUID v4 para correlationId, garantizando unicidad global.
     */
    @Override
    public String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String generateExternalReference() {
        return UUID.randomUUID().toString();
    }

    /**
     * Genera un UUID v4 para eventId, garantizando unicidad global en entornos distribuidos.
     */
    @Override
    public String generateEventId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Genera un ID de orden único basado en un patrón de alta escalabilidad
     * similar a Twitter Snowflake, combinando timestamp, nodeId y secuencia.
     *
     * La estructura del ID es:
     * - 41 bits: timestamp en milisegundos (soporta ~69 años)
     * - 10 bits: identificador de nodo (hasta 1024 nodos)
     * - 12 bits: secuencia (hasta 4096 IDs por ms por nodo)
     */
    @Override
    public Long generateOrderId() {
        long timestamp = Instant.now().toEpochMilli();
        long sequence = orderIdCounter.incrementAndGet() & ((1 << SEQUENCE_BITS) - 1);

        // Estructura similar a Snowflake pero sin el signo negativo (63 bits en total)
        return ((timestamp << (NODE_ID_BITS + SEQUENCE_BITS)) |
                (nodeId << SEQUENCE_BITS) |
                sequence);
    }

    /**
     * Alternativa que utiliza Redis para generar IDs de orden centralizados
     * cuando la secuencialidad estricta es un requisito.
     */
    public Mono<Long> generateOrderIdWithRedis() {
        return redisTemplate.opsForValue().increment(ORDER_ID_KEY)
                .defaultIfEmpty(1L)
                .onErrorReturn(orderIdCounter.incrementAndGet());
    }
}