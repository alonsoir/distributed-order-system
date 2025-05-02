package com.example.order.service;

import reactor.core.publisher.Mono;

/**
 * Interface for checking Redis availability
 */
public interface RedisStatusChecker {

    /**
     * Checks if Redis is available
     *
     * @return Mono<Boolean> indicating if Redis is available (true) or not (false)
     */
    Mono<Boolean> isRedisAvailable();
}