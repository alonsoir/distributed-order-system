package com.example.order.actuator;

import com.example.order.service.DynamicOrderService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gestor centralizado para la configuración de estrategias de Saga.
 *
 * Proporciona múltiples fuentes de configuración con prioridades:
 * 1. Cambios directos a través de API (prioridad más alta)
 * 2. Variables de entorno/propiedades
 * 3. ConfigMaps de Kubernetes
 * 4. Archivos de configuración montados
 *
 * Incluye:
 * - Detección de cambios automática
 * - API para cambios manuales
 * - Registro de auditoría
 * - Métricas detalladas
 * - Alta disponibilidad
 * - Resiliencia a fallos
 * - Conciliación de configuraciones
 */
@Component
@Endpoint(id = "saga-strategy")
@ConditionalOnProperty(name = "order.service.strategy.manager.enabled", havingValue = "true", matchIfMissing = true)
public class StrategyConfigurationManager implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(StrategyConfigurationManager.class);

    // Claves de configuración
    private static final String PROP_DEFAULT_STRATEGY = "order.service.strategy.default-strategy";
    private static final String CONFIG_FILE_PATH = "/config/saga-strategy.conf";
    private static final String STRATEGY_SOURCE_METRIC = "order.strategy.source";
    private static final String STRATEGY_CHANGE_METRIC = "order.strategy.changes";
    private static final String STRATEGY_DETECTION_METRIC = "order.strategy.detection.time";

    // Servicios principales
    private final DynamicOrderService orderService;
    private final Environment environment;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationContext applicationContext;

    // Estado interno
    private final Map<String, String> auditLog = new ConcurrentHashMap<>();
    private final AtomicReference<SourcePriority> lastActiveSource = new AtomicReference<>(SourcePriority.NONE);
    private final AtomicReference<String> manualOverrideStrategy = new AtomicReference<>();
    private final Set<String> knownSources = ConcurrentHashMap.newKeySet();

    @Value("${order.service.strategy.manager.config-file:#{null}}")
    private String configFilePath;

    @Value("${order.service.strategy.manager.enable-cloud-events:true}")
    private boolean enableCloudEvents;

    @Value("${order.service.strategy.manager.reconciliation-interval-ms:60000}")
    private long reconciliationIntervalMs;

    // Enumeración para prioridad de fuentes
    private enum SourcePriority {
        MANUAL(0, "manual"),
        ENVIRONMENT(1, "environment"),
        KUBERNETES(2, "kubernetes"),
        FILE(3, "file"),
        NONE(99, "none");

        private final int priority;
        private final String label;

        SourcePriority(int priority, String label) {
            this.priority = priority;
            this.label = label;
        }

        public boolean isHigherPriorityThan(SourcePriority other) {
            return this.priority < other.priority;
        }

        public String getLabel() {
            return label;
        }
    }

    public StrategyConfigurationManager(
            DynamicOrderService orderService,
            Environment environment,
            MeterRegistry meterRegistry,
            ApplicationEventPublisher eventPublisher,
            ApplicationContext applicationContext) {
        this.orderService = orderService;
        this.environment = environment;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
        this.applicationContext = applicationContext;

        // Inicializar métricas de gauge
        meterRegistry.gauge("order.strategy.active",
                Tags.of(Tag.of("strategy", "unknown")),
                this,
                manager -> manager.getActiveStrategyValue());
    }

    @Override
    public void afterPropertiesSet() {
        // Inicializar todas las fuentes conocidas
        knownSources.add(SourcePriority.MANUAL.getLabel());
        knownSources.add(SourcePriority.ENVIRONMENT.getLabel());
        knownSources.add(SourcePriority.KUBERNETES.getLabel());
        knownSources.add(SourcePriority.FILE.getLabel());

        // Establecer estado inicial usando la configuración disponible
        updateConfigurationFromAllSources();
        log.info("Strategy Configuration Manager initialized with strategy: {}",
                orderService.getDefaultStrategy());

        // Inicializar métricas para cada fuente conocida
        for (String source : knownSources) {
            meterRegistry.counter(STRATEGY_SOURCE_METRIC, "source", source);
        }
    }

    /**
     * Actualización programada para reconciliar configuraciones de todas las fuentes.
     * Esto garantiza que incluso si se perdió algún evento de cambio, la configuración
     * eventualmente se actualice.
     */
    @Scheduled(fixedDelayString = "${order.service.strategy.manager.check-interval:30000}")
    public void updateConfigurationFromAllSources() {
        Timer.Sample timer = Timer.start(meterRegistry);

        try {
            // Primero verificar override manual (mayor prioridad)
            if (updateFromManualOverride()) {
                timer.stop(meterRegistry.timer(STRATEGY_DETECTION_METRIC, "source", "manual", "result", "applied"));
                return;
            }

            // Luego verificar propiedades/variables de entorno
            if (updateFromEnvironment()) {
                timer.stop(meterRegistry.timer(STRATEGY_DETECTION_METRIC, "source", "environment", "result", "applied"));
                return;
            }

            // Finalmente verificar archivos de configuración (menor prioridad)
            if (updateFromConfigFile()) {
                timer.stop(meterRegistry.timer(STRATEGY_DETECTION_METRIC, "source", "file", "result", "applied"));
                return;
            }

            // Si no se encontró ninguna configuración
            timer.stop(meterRegistry.timer(STRATEGY_DETECTION_METRIC, "source", "none", "result", "no_change"));
        } catch (Exception e) {
            log.error("Error updating configuration from sources", e);
            timer.stop(meterRegistry.timer(STRATEGY_DETECTION_METRIC, "result", "error"));
            meterRegistry.counter("order.strategy.errors", "type", e.getClass().getSimpleName()).increment();
        }
    }

    /**
     * Recibe eventos de actualización de configuración de Spring
     */
    @EventListener
    public void handleConfigurationEvent(ApplicationEvent event) {
        if (event instanceof ContextRefreshedEvent) {
            log.info("Application context refreshed, updating configuration");
            updateConfigurationFromAllSources();
        } else if (enableCloudEvents && (event instanceof RefreshScopeRefreshedEvent ||
                event instanceof EnvironmentChangeEvent)) {
            log.info("Cloud configuration refreshed, updating from environment");
            Timer.Sample timer = Timer.start(meterRegistry);
            if (updateFromEnvironment()) {
                timer.stop(meterRegistry.timer(STRATEGY_DETECTION_METRIC,
                        "source", "cloud_event", "result", "applied"));
            } else {
                timer.stop(meterRegistry.timer(STRATEGY_DETECTION_METRIC,
                        "source", "cloud_event", "result", "no_change"));
            }
        }
    }

    /**
     * Endpoint Actuator para leer la configuración actual de estrategia
     */
    @ReadOperation
    public Map<String, Object> getStrategyInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("currentStrategy", orderService.getDefaultStrategy());
        info.put("availableStrategies", orderService.getAvailableStrategies());
        info.put("activeSource", lastActiveSource.get().getLabel());
        info.put("manualOverride", manualOverrideStrategy.get() != null);
        info.put("lastChanges", getLimitedAuditLog(10));

        // Añadir información de todas las fuentes
        Map<String, String> sources = new HashMap<>();
        try {
            sources.put("environment", getEnvironmentStrategy());
            sources.put("file", getFileStrategy());
            sources.put("manual", manualOverrideStrategy.get());
        } catch (Exception e) {
            log.warn("Error collecting source information", e);
        }
        info.put("sources", sources);

        return info;
    }

    /**
     * Endpoint Actuator para establecer manualmente la estrategia
     */
    @WriteOperation
    public Map<String, Object> updateStrategy(String strategy, boolean override) {
        Map<String, Object> result = new HashMap<>();

        try {
            if (override) {
                // Establecer override manual (mayor prioridad)
                setManualOverride(strategy);
                result.put("status", "success");
                result.put("message", "Manual override set: " + strategy);
            } else if (strategy == null) {
                // Limpiar override manual
                clearManualOverride();
                result.put("status", "success");
                result.put("message", "Manual override cleared");
            } else {
                // Cambio normal sin override
                boolean changed = applyStrategy(strategy, SourcePriority.MANUAL);
                result.put("status", "success");
                result.put("changed", changed);
                result.put("strategy", strategy);
            }
        } catch (IllegalArgumentException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        // Añadir información actual
        result.put("currentStrategy", orderService.getDefaultStrategy());
        result.put("activeSource", lastActiveSource.get().getLabel());

        return result;
    }

    /**
     * Establece manualmente la estrategia, con la mayor prioridad
     */
    public void setManualOverride(String strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy cannot be null for manual override");
        }

        log.info("Setting manual override strategy: {}", strategy);
        manualOverrideStrategy.set(strategy);
        boolean applied = applyStrategy(strategy, SourcePriority.MANUAL);

        if (applied) {
            logStrategyChange("Manual override set to: " + strategy);
            meterRegistry.counter(STRATEGY_CHANGE_METRIC,
                    "source", "manual_override",
                    "strategy", strategy).increment();
        }
    }

    /**
     * Limpia el override manual, permitiendo que otras fuentes determinen la estrategia
     */
    public void clearManualOverride() {
        log.info("Clearing manual override strategy");
        String previous = manualOverrideStrategy.getAndSet(null);

        if (previous != null) {
            // Registrar el cambio
            logStrategyChange("Manual override cleared, was: " + previous);
            meterRegistry.counter(STRATEGY_CHANGE_METRIC,
                    "source", "manual_override_clear",
                    "previous", previous).increment();

            // Actualizar desde otras fuentes
            updateConfigurationFromAllSources();
        }
    }

    /**
     * Intenta actualizar la configuración desde override manual
     * @return true si se aplicó un cambio
     */
    private boolean updateFromManualOverride() {
        String manualStrategy = manualOverrideStrategy.get();
        if (manualStrategy != null) {
            return applyStrategy(manualStrategy, SourcePriority.MANUAL);
        }
        return false;
    }

    /**
     * Intenta actualizar la configuración desde variables de entorno/propiedades
     * @return true si se aplicó un cambio
     */
    private boolean updateFromEnvironment() {
        String envStrategy = getEnvironmentStrategy();
        if (envStrategy != null && !envStrategy.isEmpty()) {
            return applyStrategy(envStrategy, SourcePriority.ENVIRONMENT);
        }
        return false;
    }

    /**
     * Intenta actualizar la configuración desde archivo
     * @return true si se aplicó un cambio
     */
    private boolean updateFromConfigFile() {
        try {
            String fileStrategy = getFileStrategy();
            if (fileStrategy != null && !fileStrategy.isEmpty()) {
                return applyStrategy(fileStrategy, SourcePriority.FILE);
            }
        } catch (IOException e) {
            log.warn("Error reading strategy from config file: {}", e.getMessage());
            meterRegistry.counter("order.strategy.file.errors").increment();
        }
        return false;
    }

    /**
     * Obtiene la estrategia desde variables de entorno/propiedades
     */
    private String getEnvironmentStrategy() {
        return environment.getProperty(PROP_DEFAULT_STRATEGY);
    }

    /**
     * Obtiene la estrategia desde archivo de configuración
     */
    private String getFileStrategy() throws IOException {
        String path = configFilePath != null ? configFilePath : CONFIG_FILE_PATH;
        Path configFile = Paths.get(path);
        if (Files.exists(configFile)) {
            return Files.readString(configFile).trim();
        }
        return null;
    }

    /**
     * Aplica una estrategia si tiene prioridad suficiente y es válida
     * @return true si se aplicó un cambio
     */
    private boolean applyStrategy(String strategy, SourcePriority source) {
        // Verificar si la fuente tiene suficiente prioridad
        if (source.isHigherPriorityThan(lastActiveSource.get()) ||
                (source == lastActiveSource.get() && !strategy.equals(orderService.getDefaultStrategy()))) {

            try {
                log.debug("Attempting to apply strategy '{}' from source '{}'", strategy, source.getLabel());
                boolean changed = orderService.setDefaultStrategy(strategy);

                if (changed) {
                    // Actualizar fuente activa y registrar el cambio
                    SourcePriority previousSource = lastActiveSource.getAndSet(source);

                    logStrategyChange(String.format("Strategy changed to '%s' from source '%s' (previous source: '%s')",
                            strategy, source.getLabel(), previousSource.getLabel()));

                    // Actualizar métricas
                    meterRegistry.counter(STRATEGY_SOURCE_METRIC,
                            "source", source.getLabel()).increment();

                    meterRegistry.counter(STRATEGY_CHANGE_METRIC,
                            "source", source.getLabel(),
                            "strategy", strategy).increment();

                    // Actualizar métricas de gauge
                    updateStrategyGauges(strategy);

                    return true;
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid strategy '{}' from source '{}': {}",
                        strategy, source.getLabel(), e.getMessage());
                meterRegistry.counter("order.strategy.invalid",
                        "source", source.getLabel(),
                        "strategy", strategy).increment();
            }
        }
        return false;
    }

    /**
     * Actualiza las métricas de gauge para la estrategia activa
     */
    private void updateStrategyGauges(String strategy) {
        // Actualizar gauges para cada tipo de estrategia
        for (String availableStrategy : orderService.getAvailableStrategies()) {
            meterRegistry.gauge("order.strategy.active." + availableStrategy,
                    this,
                    manager -> availableStrategy.equals(strategy) ? 1 : 0);
        }
    }

    /**
     * Obtiene valor numérico para métricas de estrategia activa
     */
    private double getActiveStrategyValue() {
        switch (orderService.getDefaultStrategy()) {
            case "atLeastOnce":
                return 1.0;
            case "atMostOnce":
                return 2.0;
            default:
                return 0.0;
        }
    }

    /**
     * Registra un cambio de estrategia en el log de auditoría
     */
    private void logStrategyChange(String message) {
        String timestamp = Instant.now().toString();
        auditLog.put(timestamp, message);
        log.info("Strategy change: {}", message);

        // Si hay muchas entradas en el log, eliminar algunas antiguas
        if (auditLog.size() > 100) {
            List<String> keys = new ArrayList<>(auditLog.keySet());
            keys.sort(String::compareTo);

            // Eliminar las 20 entradas más antiguas
            keys.stream()
                    .limit(20)
                    .forEach(auditLog::remove);
        }
    }

    /**
     * Obtiene las últimas entradas del log de auditoría
     */
    private Map<String, String> getLimitedAuditLog(int limit) {
        List<String> keys = new ArrayList<>(auditLog.keySet());
        keys.sort(Comparator.reverseOrder());  // Ordenar del más reciente al más antiguo

        Map<String, String> result = new LinkedHashMap<>();
        keys.stream()
                .limit(limit)
                .forEach(key -> result.put(key, auditLog.get(key)));

        return result;
    }

    /**
     * Obtiene estadísticas sobre el uso de estrategias
     */
    public Map<String, Object> getStrategyStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("currentStrategy", orderService.getDefaultStrategy());
        stats.put("activeSource", lastActiveSource.get().getLabel());
        stats.put("availableStrategies", orderService.getAvailableStrategies());
        stats.put("changeCount", auditLog.size());
        return stats;
    }

    /**
     * Fuerza una reconciliación completa de la configuración
     */
    public void forceReconciliation() {
        log.info("Forcing configuration reconciliation");
        // Establecer fuente como NONE para permitir que cualquier fuente sea considerada
        lastActiveSource.set(SourcePriority.NONE);
        updateConfigurationFromAllSources();
    }

    /**
     * Programa reconciliación periódica para alta disponibilidad
     */
    @Scheduled(fixedDelayString = "${order.service.strategy.manager.reconciliation-interval-ms:60000}")
    public void periodicReconciliation() {
        // Solo hacer reconciliación si ha pasado suficiente tiempo desde el último cambio
        // para evitar oscilaciones en la configuración
        if (Duration.between(Instant.now(),
                Instant.parse(getLatestChangeTimestamp())).toMillis() > reconciliationIntervalMs) {
            log.debug("Performing periodic configuration reconciliation");
            forceReconciliation();
        }
    }

    /**
     * Obtiene la marca de tiempo del último cambio
     */
    private String getLatestChangeTimestamp() {
        if (auditLog.isEmpty()) {
            return Instant.EPOCH.toString();
        }

        return auditLog.keySet().stream()
                .max(String::compareTo)
                .orElse(Instant.EPOCH.toString());
    }
}