package com.example.order.controller;

import com.example.order.service.DynamicOrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/management")
public class OrderServiceManagementController {
    private final DynamicOrderService orderService;

    public OrderServiceManagementController(DynamicOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/strategies")
    public ResponseEntity<Map<String, Object>> getStrategiesInfo() {
        Map<String, Object> response = new HashMap<>();
        response.put("currentStrategy", orderService.getDefaultStrategy());
        response.put("availableStrategies", orderService.getAvailableStrategies());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/strategies/default")
    public ResponseEntity<Map<String, Object>> setDefaultStrategy(@RequestBody Map<String, String> request) {
        String strategy = request.get("strategy");
        if (strategy == null) {
            return ResponseEntity.badRequest().body(
                    Collections.singletonMap("error", "Strategy name is required"));
        }

        try {
            boolean changed = orderService.setDefaultStrategy(strategy);
            Map<String, Object> response = new HashMap<>();
            response.put("strategy", strategy);
            response.put("changed", changed);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    Collections.singletonMap("error", e.getMessage()));
        }
    }
}
