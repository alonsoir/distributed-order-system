package com.example.order.actuator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

import static com.example.order.config.StrategyMetricsConstants.*;

/**
 * Controller REST para gestión de estrategias de Saga.
 *
 * Proporciona endpoints para:
 * - Consultar información de estrategias
 * - Actualizar estrategias manualmente
 * - Limpiar overrides manuales
 * - Forzar reconciliación de configuración
 * - Obtener estadísticas de uso
 */
@RestController
@RequestMapping(API_BASE_PATH)
public class StrategyManagementController {

    private final StrategyConfigurationManager configManager;

    public StrategyManagementController(StrategyConfigurationManager configManager) {
        this.configManager = configManager;
    }

    @GetMapping(ENDPOINT_STRATEGY)
    public ResponseEntity<Map<String, Object>> getStrategyInfo() {
        return ResponseEntity.ok(configManager.getStrategyInfo());
    }

    @PostMapping(ENDPOINT_STRATEGY)
    public ResponseEntity<Map<String, Object>> updateStrategy(
            @RequestBody Map<String, Object> request) {

        String strategy = (String) request.get(REQUEST_STRATEGY);
        boolean override = Boolean.TRUE.equals(request.get(REQUEST_OVERRIDE));

        Map<String, Object> result = configManager.updateStrategy(strategy, override);

        if (RESULT_ERROR.equals(result.get(JSON_STATUS))) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }

    @DeleteMapping(ENDPOINT_STRATEGY_OVERRIDE)
    public ResponseEntity<Map<String, Object>> clearManualOverride() {
        configManager.clearManualOverride();

        Map<String, Object> result = new HashMap<>();
        result.put(JSON_STATUS, RESULT_SUCCESS);
        result.put(JSON_MESSAGE, MSG_MANUAL_OVERRIDE_CLEARED);
        result.put(JSON_CURRENT_STRATEGY, configManager.getStrategyInfo().get(JSON_CURRENT_STRATEGY));

        return ResponseEntity.ok(result);
    }

    @PostMapping(ENDPOINT_STRATEGY_RECONCILE)
    public ResponseEntity<Map<String, Object>> forceReconciliation() {
        configManager.forceReconciliation();

        Map<String, Object> result = new HashMap<>();
        result.put(JSON_STATUS, RESULT_SUCCESS);
        result.put(JSON_MESSAGE, MSG_RECONCILIATION_COMPLETED);
        result.put(JSON_CURRENT_STRATEGY, configManager.getStrategyInfo().get(JSON_CURRENT_STRATEGY));

        return ResponseEntity.ok(result);
    }

    @GetMapping(ENDPOINT_STRATEGY_STATS)
    public ResponseEntity<Map<String, Object>> getStrategyStatistics() {
        return ResponseEntity.ok(configManager.getStrategyStatistics());
    }
}