package com.example.order.config;

/**
 * Constantes para métricas y configuración de estrategias de orden.
 *
 * Centraliza todas las cadenas literales relacionadas con métricas de estrategias
 * para evitar duplicación y facilitar mantenimiento.
 */
public final class StrategyMetricsConstants {

    // =============================================
    // TAGS DE MÉTRICAS
    // =============================================

    /** Tag para identificar la estrategia en métricas */
    public static final String TAG_STRATEGY = "strategy";

    /** Tag para identificar la fuente de configuración */
    public static final String TAG_SOURCE = "source";

    /** Tag para identificar el resultado de una operación */
    public static final String TAG_RESULT = "result";

    /** Tag para identificar el tipo de error */
    public static final String TAG_ERROR_TYPE = "error_type";

    /** Tag para identificar el número de intento */
    public static final String TAG_ATTEMPT = "attempt";

    /** Tag para identificar el estado anterior */
    public static final String TAG_PREVIOUS = "previous";

    // =============================================
    // VALORES DE ESTRATEGIA
    // =============================================

    /** Valor para estrategia At Least Once */
    public static final String STRATEGY_AT_LEAST_ONCE = "atLeastOnce";

    /** Valor para estrategia At Most Once */
    public static final String STRATEGY_AT_MOST_ONCE = "atMostOnce";

    /** Valor por defecto cuando la estrategia es desconocida */
    public static final String STRATEGY_UNKNOWN = "unknown";

    // =============================================
    // FUENTES DE CONFIGURACIÓN
    // =============================================

    /** Fuente de configuración manual */
    public static final String SOURCE_MANUAL = "manual";

    /** Fuente de configuración desde variables de entorno */
    public static final String SOURCE_ENVIRONMENT = "environment";

    /** Fuente de configuración desde Kubernetes */
    public static final String SOURCE_KUBERNETES = "kubernetes";

    /** Fuente de configuración desde archivo */
    public static final String SOURCE_FILE = "file";

    /** Fuente de configuración desde eventos de cloud */
    public static final String SOURCE_CLOUD_EVENT = "cloud_event";

    /** Override manual de configuración */
    public static final String SOURCE_MANUAL_OVERRIDE = "manual_override";

    /** Limpieza de override manual */
    public static final String SOURCE_MANUAL_OVERRIDE_CLEAR = "manual_override_clear";

    /** Sin fuente definida */
    public static final String SOURCE_NONE = "none";

    // =============================================
    // RESULTADOS DE OPERACIONES
    // =============================================

    /** Resultado: operación aplicada exitosamente */
    public static final String RESULT_APPLIED = "applied";

    /** Resultado: sin cambios */
    public static final String RESULT_NO_CHANGE = "no_change";

    /** Resultado: error en la operación */
    public static final String RESULT_ERROR = "error";

    /** Resultado: éxito */
    public static final String RESULT_SUCCESS = "success";

    // =============================================
    // NOMBRES DE MÉTRICAS
    // =============================================

    /** Métrica para estrategia activa */
    public static final String METRIC_STRATEGY_ACTIVE = "order.strategy.active";

    /** Métrica para fuente de estrategia */
    public static final String METRIC_STRATEGY_SOURCE = "order.strategy.source";

    /** Métrica para cambios de estrategia */
    public static final String METRIC_STRATEGY_CHANGES = "order.strategy.changes";

    /** Métrica para tiempo de detección de estrategia */
    public static final String METRIC_STRATEGY_DETECTION_TIME = "order.strategy.detection.time";

    /** Métrica para errores de estrategia */
    public static final String METRIC_STRATEGY_ERRORS = "order.strategy.errors";

    /** Métrica para estrategias inválidas */
    public static final String METRIC_STRATEGY_INVALID = "order.strategy.invalid";

    /** Métrica para errores de archivos */
    public static final String METRIC_STRATEGY_FILE_ERRORS = "order.strategy.file.errors";

    // =============================================
    // PROPIEDADES DE CONFIGURACIÓN
    // =============================================

    /** Propiedad para la estrategia por defecto */
    public static final String PROP_DEFAULT_STRATEGY = "order.service.strategy.default-strategy";

    /** Propiedad para habilitar el gestor de configuración */
    public static final String PROP_MANAGER_ENABLED = "order.service.strategy.manager.enabled";

    /** Propiedad para archivo de configuración personalizado */
    public static final String PROP_CONFIG_FILE = "order.service.strategy.manager.config-file";

    /** Propiedad para habilitar eventos de cloud */
    public static final String PROP_ENABLE_CLOUD_EVENTS = "order.service.strategy.manager.enable-cloud-events";

    /** Propiedad para intervalo de reconciliación */
    public static final String PROP_RECONCILIATION_INTERVAL = "order.service.strategy.manager.reconciliation-interval-ms";

    /** Propiedad para intervalo de verificación */
    public static final String PROP_CHECK_INTERVAL = "order.service.strategy.manager.check-interval";

    // =============================================
    // RUTAS Y ARCHIVOS
    // =============================================

    /** Ruta por defecto del archivo de configuración */
    public static final String DEFAULT_CONFIG_FILE_PATH = "/config/saga-strategy.conf";

    // =============================================
    // VALORES POR DEFECTO
    // =============================================

    /** Valor por defecto para habilitar el gestor */
    public static final String DEFAULT_MANAGER_ENABLED = "true";

    /** Valor por defecto para eventos de cloud */
    public static final String DEFAULT_ENABLE_CLOUD_EVENTS = "true";

    /** Valor por defecto para intervalo de verificación (30 segundos) */
    public static final String DEFAULT_CHECK_INTERVAL = "30000";

    /** Valor por defecto para intervalo de reconciliación (60 segundos) */
    public static final String DEFAULT_RECONCILIATION_INTERVAL = "60000";

    /** Número máximo de entradas en log de auditoría */
    public static final int MAX_AUDIT_LOG_ENTRIES = 100;

    /** Número de entradas a eliminar cuando se excede el máximo */
    public static final int AUDIT_LOG_CLEANUP_COUNT = 20;

    /** Límite por defecto para consultas de log de auditoría */
    public static final int DEFAULT_AUDIT_LOG_LIMIT = 10;

    // =============================================
    // CONSTRUCTOR PRIVADO
    // =============================================

    /**
     * Constructor privado para prevenir instanciación.
     * Esta es una clase de constantes que no debe ser instanciada.
     */
    private StrategyMetricsConstants() {
        throw new AssertionError("StrategyMetricsConstants no debe ser instanciada");
    }
}