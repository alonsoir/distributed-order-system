package com.example.order.events;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import static com.example.order.config.RedisConfig.EVENT_TOPIC;


@Component("genericEventPublisher") // Nombre único, todo sospechosa para desaparecer tras refactorizaciones
public class EventPublisher {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    public EventPublisher(ReactiveRedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public <T> Mono<Long> publish(T payload) {
        var wrapper = new EventWrapper<>(payload.getClass().getSimpleName(), payload);
        return redisTemplate.convertAndSend(EVENT_TOPIC, wrapper);
    }

    // Sobrecarga con topic específico si lo necesitas
    public <T> Mono<Long> publish(String topic, T payload) {
        var wrapper = new EventWrapper<>(payload.getClass().getSimpleName(), payload);
        return redisTemplate.convertAndSend(topic, wrapper);
    }
}