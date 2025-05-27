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

import static com.example.order.config.StrategyMetricsConstants.*;

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
@ConditionalOnProperty(name = PROP_MANAGER_ENABLED, havingValue = DEFAULT_MANAGER_ENABLED, matchIfMissing = true)
public class StrategyConfigurationManager implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(StrategyConfigurationManager.class);

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

    @Value("${" + PROP_CONFIG_FILE + ":#{null}}")
    private String configFilePath;

    @Value("${" + PROP_ENABLE_CLOUD_EVENTS + ":" + DEFAULT_ENABLE_CLOUD_EVENTS + "}")
    private boolean enableCloudEvents;

    @Value("${" + PROP_RECONCILIATION_INTERVAL + ":" + DEFAULT_RECONCILIATION_INTERVAL + "}")
    private long reconciliationIntervalMs;

    // Enumeración para prioridad de fuentes
    private enum SourcePriority {
        MANUAL(0, SOURCE_MANUAL),
        ENVIRONMENT(1, SOURCE_ENVIRONMENT),
        KUBERNETES(2, SOURCE_KUBERNETES),
        FILE(3, SOURCE_FILE),
        NONE(99, SOURCE_NONE);

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
        meterRegistry.gauge(METRIC_STRATEGY_ACTIVE,
                Tags.of(Tag.of(TAG_STRATEGY, STRATEGY_UNKNOWN)),
                this,
                manager -> manager.getActiveStrategyValue());
    }

    @Override
    public void afterPropertiesSet() {
        // Inicializar todas las fuentes conocidas
        knownSources.add(SOURCE_MANUAL);
        knownSources.add(SOURCE_ENVIRONMENT);
        knownSources.add(SOURCE_KUBERNETES);
        knownSources.add(SOURCE_FILE);

        // Establecer estado inicial usando la configuración disponible
        updateConfigurationFromAllSources();
        log.info("Strategy Configuration Manager initialized with strategy: {}",
                orderService.getDefaultStrategy());

        // Inicializar métricas para cada fuente conocida
        for (String source : knownSources) {
            meterRegistry.counter(METRIC_STRATEGY_SOURCE, TAG_SOURCE, source);
        }
    }

    /**
     * Actualización programada para reconciliar configuraciones de todas las fuentes.
     */
    @Scheduled(fixedDelayString = "${" + PROP_CHECK_INTERVAL + ":" + DEFAULT_CHECK_INTERVAL + "}")
    public void updateConfigurationFromAllSources() {
        Timer.Sample timer = Timer.start(meterRegistry);

        try {
            // Primero verificar override manual (mayor prioridad)
            if (updateFromManualOverride()) {
                timer.stop(meterRegistry.timer(METRIC_STRATEGY_DETECTION_TIME,
                        TAG_SOURCE, SOURCE_MANUAL, TAG_RESULT, RESULT_APPLIED));
                return;
            }

            // Luego verificar propiedades/variables de entorno
            if (updateFromEnvironment()) {
                timer.stop(meterRegistry.timer(METRIC_STRATEGY_DETECTION_TIME,
                        TAG_SOURCE, SOURCE_ENVIRONMENT, TAG_RESULT, RESULT_APPLIED));
                return;
            }

            // Finalmente verificar archivos de configuración (menor prioridad)
            if (updateFromConfigFile()) {
                timer.stop(meterRegistry.timer(METRIC_STRATEGY_DETECTION_TIME,
                        TAG_SOURCE, SOURCE_FILE, TAG_RESULT, RESULT_APPLIED));
                return;
            }

            // Si no se encontró ninguna configuración
            timer.stop(meterRegistry.timer(METRIC_STRATEGY_DETECTION_TIME,
                    TAG_SOURCE, SOURCE_NONE, TAG_RESULT, RESULT_NO_CHANGE));
        } catch (Exception e) {
            log.error("Error updating configuration from sources", e);
            timer.stop(meterRegistry.timer(METRIC_STRATEGY_DETECTION_TIME, TAG_RESULT, RESULT_ERROR));
            meterRegistry.counter(METRIC_STRATEGY_ERRORS, TAG_ERROR_TYPE, e.getClass().getSimpleName()).increment();
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
                timer.stop(meterRegistry.timer(METRIC_STRATEGY_DETECTION_TIME,
                        TAG_SOURCE, SOURCE_CLOUD_EVENT, TAG_RESULT, RESULT_APPLIED));
            } else {
                timer.stop(meterRegistry.timer(METRIC_STRATEGY_DETECTION_TIME,
                        TAG_SOURCE, SOURCE_CLOUD_EVENT, TAG_RESULT, RESULT_NO_CHANGE));
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
        info.put("lastChanges", getLimitedAuditLog(DEFAULT_AUDIT_LOG_LIMIT));

        // Añadir información de todas las fuentes
        Map<String, String> sources = new HashMap<>();
        try {
            sources.put(SOURCE_ENVIRONMENT, getEnvironmentStrategy());
            sources.put(SOURCE_FILE, getFileStrategy());
            sources.put(SOURCE_MANUAL, manualOverrideStrategy.get());
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
                result.put("status", RESULT_SUCCESS);
                result.put("message", "Manual override set: " + strategy);
            } else if (strategy == null) {
                // Limpiar override manual
                clearManualOverride();
                result.put("status", RESULT_SUCCESS);
                result.put("message", "Manual override cleared");
            } else {
                // Cambio normal sin override
                boolean changed = applyStrategy(strategy, SourcePriority.MANUAL);
                result.put("status", RESULT_SUCCESS);
                result.put("changed", changed);
                result.put(TAG_STRATEGY, strategy);
            }
        } catch (IllegalArgumentException e) {
            result.put("status", RESULT_ERROR);
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
            meterRegistry.counter(METRIC_STRATEGY_CHANGES,
                    TAG_SOURCE, SOURCE_MANUAL_OVERRIDE,
                    TAG_STRATEGY, strategy).increment();
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
            meterRegistry.counter(METRIC_STRATEGY_CHANGES,
                    TAG_SOURCE, SOURCE_MANUAL_OVERRIDE_CLEAR,
                    TAG_PREVIOUS, previous).increment();

            // Actualizar desde otras fuentes
            updateConfigurationFromAllSources();
        }
    }

    /**
     * Intenta actualizar la configuración desde override manual
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
     */
    private boolean updateFromConfigFile() {
        try {
            String fileStrategy = getFileStrategy();
            if (fileStrategy != null && !fileStrategy.isEmpty()) {
                return applyStrategy(fileStrategy, SourcePriority.FILE);
            }
        } catch (IOException e) {
            log.warn("Error reading strategy from config file: {}", e.getMessage());
            meterRegistry.counter(METRIC_STRATEGY_FILE_ERRORS).increment();
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
        String path = configFilePath != null ? configFilePath : DEFAULT_CONFIG_FILE_PATH;
        Path configFile = Paths.get(path);
        if (Files.exists(configFile)) {
            return Files.readString(configFile).trim();
        }
        return null;
    }

    /**
     * Aplica una estrategia si tiene prioridad suficiente y es válida
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
                    meterRegistry.counter(METRIC_STRATEGY_SOURCE, TAG_SOURCE, source.getLabel()).increment();

                    meterRegistry.counter(METRIC_STRATEGY_CHANGES,
                            TAG_SOURCE, source.getLabel(),
                            TAG_STRATEGY, strategy).increment();

                    // Actualizar métricas de gauge
                    updateStrategyGauges(strategy);

                    return true;
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid strategy '{}' from source '{}': {}",
                        strategy, source.getLabel(), e.getMessage());
                meterRegistry.counter(METRIC_STRATEGY_INVALID,
                        TAG_SOURCE, source.getLabel(),
                        TAG_STRATEGY, strategy).increment();
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
            meterRegistry.gauge(METRIC_STRATEGY_ACTIVE + "." + availableStrategy,
                    this,
                    manager -> availableStrategy.equals(strategy) ? 1 : 0);
        }
    }

    /**
     * Obtiene valor numérico para métricas de estrategia activa
     */
    private double getActiveStrategyValue() {
        switch (orderService.getDefaultStrategy()) {
            case STRATEGY_AT_LEAST_ONCE:
                return 1.0;
            case STRATEGY_AT_MOST_ONCE:
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
        if (auditLog.size() > MAX_AUDIT_LOG_ENTRIES) {
            List<String> keys = new ArrayList<>(auditLog.keySet());
            keys.sort(String::compareTo);

            // Eliminar las entradas más antiguas
            keys.stream()
                    .limit(AUDIT_LOG_CLEANUP_COUNT)
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
    @Scheduled(fixedDelayString = "${" + PROP_RECONCILIATION_INTERVAL + ":" + DEFAULT_RECONCILIATION_INTERVAL + "}")
    public void periodicReconciliation() {
        // Solo hacer reconciliación si ha pasado suficiente tiempo desde el último cambio
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