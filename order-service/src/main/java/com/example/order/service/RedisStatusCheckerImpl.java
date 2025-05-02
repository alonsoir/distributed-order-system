package com.example.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class RedisStatusCheckerImpl implements RedisStatusChecker {
    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    public Mono<Boolean> isRedisAvailable() {
        return redisTemplate.opsForValue().get("health-check")
                .thenReturn(true)
                .onErrorResume(e -> Mono.just(false));
    }
}