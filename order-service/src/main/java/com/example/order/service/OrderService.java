package com.example.order.service;

import com.example.order.domain.Order;
import reactor.core.publisher.Mono;

public interface OrderService {
    Mono<Order> processOrder(String externalReference,int quantity, double amount);

}