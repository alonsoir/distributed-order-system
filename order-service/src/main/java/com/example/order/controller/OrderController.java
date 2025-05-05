package com.example.order.controller;

import com.example.order.domain.Order;
import com.example.order.service.IdGenerator;
import com.example.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;
    private final IdGenerator idGenerator;
    record OrderRequest(String externalReference, int quantity, double amount) {}

    @PostMapping
    public Mono<Order> createOrder(@RequestBody OrderRequest request) {
        Long orderId = idGenerator.generateOrderId();
        return orderService.processOrder(orderId, request.externalReference(),request.quantity(), request.amount());
    }
}