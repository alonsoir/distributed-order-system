package com.example.order.actuator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/management/saga")
public class StrategyManagementController {

    private final StrategyConfigurationManager configManager;

    public StrategyManagementController(StrategyConfigurationManager configManager) {
        this.configManager = configManager;
    }

    @GetMapping("/strategy")
    public ResponseEntity<Map<String, Object>> getStrategyInfo() {
        return ResponseEntity.ok(configManager.getStrategyInfo());
    }

    @PostMapping("/strategy")
    public ResponseEntity<Map<String, Object>> updateStrategy(
            @RequestBody Map<String, Object> request) {

        String strategy = (String) request.get("strategy");
        boolean override = Boolean.TRUE.equals(request.get("override"));

        Map<String, Object> result = configManager.updateStrategy(strategy, override);

        if ("error".equals(result.get("status"))) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/strategy/override")
    public ResponseEntity<Map<String, Object>> clearManualOverride() {
        configManager.clearManualOverride();

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "Manual override cleared");
        result.put("currentStrategy", configManager.getStrategyInfo().get("currentStrategy"));

        return ResponseEntity.ok(result);
    }

    @PostMapping("/strategy/reconcile")
    public ResponseEntity<Map<String, Object>> forceReconciliation() {
        configManager.forceReconciliation();

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "Reconciliation completed");
        result.put("currentStrategy", configManager.getStrategyInfo().get("currentStrategy"));

        return ResponseEntity.ok(result);
    }

    @GetMapping("/strategy/stats")
    public ResponseEntity<Map<String, Object>> getStrategyStatistics() {
        return ResponseEntity.ok(configManager.getStrategyStatistics());
    }
}