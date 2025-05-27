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
    // CLAVES DE RESPUESTA/INFO JSON
    // =============================================

    /** Clave para estrategia actual en respuestas JSON */
    public static final String JSON_CURRENT_STRATEGY = "currentStrategy";

    /** Clave para estrategias disponibles en respuestas JSON */
    public static final String JSON_AVAILABLE_STRATEGIES = "availableStrategies";

    /** Clave para fuente activa en respuestas JSON */
    public static final String JSON_ACTIVE_SOURCE = "activeSource";

    /** Clave para override manual en respuestas JSON */
    public static final String JSON_MANUAL_OVERRIDE = "manualOverride";

    /** Clave para últimos cambios en respuestas JSON */
    public static final String JSON_LAST_CHANGES = "lastChanges";

    /** Clave para fuentes de configuración en respuestas JSON */
    public static final String JSON_SOURCES = "sources";

    /** Clave para estado de operación en respuestas JSON */
    public static final String JSON_STATUS = "status";

    /** Clave para mensaje de respuesta en respuestas JSON */
    public static final String JSON_MESSAGE = "message";

    /** Clave para indicar si hubo cambio en respuestas JSON */
    public static final String JSON_CHANGED = "changed";

    /** Clave para contador de cambios en respuestas JSON */
    public static final String JSON_CHANGE_COUNT = "changeCount";

    // =============================================
    // CLAVES DE REQUEST PARA CONTROLLER
    // =============================================

    /** Clave para estrategia en requests */
    public static final String REQUEST_STRATEGY = "strategy";

    /** Clave para override en requests */
    public static final String REQUEST_OVERRIDE = "override";

    // =============================================
    // ENDPOINTS Y PATHS
    // =============================================

    /** Base path para management API */
    public static final String API_BASE_PATH = "/api/management/saga";

    /** Endpoint para estrategia */
    public static final String ENDPOINT_STRATEGY = "/strategy";

    /** Endpoint para limpiar override */
    public static final String ENDPOINT_STRATEGY_OVERRIDE = "/strategy/override";

    /** Endpoint para reconciliación */
    public static final String ENDPOINT_STRATEGY_RECONCILE = "/strategy/reconcile";

    /** Endpoint para estadísticas */
    public static final String ENDPOINT_STRATEGY_STATS = "/strategy/stats";

    // =============================================
    // MENSAJES DE RESPUESTA
    // =============================================

    /** Mensaje para override manual establecido */
    public static final String MSG_MANUAL_OVERRIDE_SET = "Manual override set: ";

    /** Mensaje para override manual limpiado */
    public static final String MSG_MANUAL_OVERRIDE_CLEARED = "Manual override cleared";

    /** Mensaje para reconciliación completada */
    public static final String MSG_RECONCILIATION_COMPLETED = "Reconciliation completed";

    /** Mensaje de error para estrategia nula */
    public static final String MSG_STRATEGY_NULL_ERROR = "Strategy cannot be null for manual override";

    // =============================================
    // MENSAJES DE LOG
    // =============================================

    /** Mensaje de log para reconciliación forzada */
    public static final String MSG_FORCING_RECONCILIATION = "Forcing configuration reconciliation";

    /** Mensaje de log para reconciliación periódica */
    public static final String MSG_PERIODIC_RECONCILIATION = "Performing periodic configuration reconciliation";

    /** Mensaje de log para contexto actualizado */
    public static final String MSG_CONTEXT_REFRESHED = "Application context refreshed, updating configuration";

    /** Mensaje de log para configuración cloud actualizada */
    public static final String MSG_CLOUD_CONFIG_REFRESHED = "Cloud configuration refreshed, updating from environment";

    /** Mensaje de log para manager inicializado */
    public static final String MSG_MANAGER_INITIALIZED = "Strategy Configuration Manager initialized with strategy: {}";

    /** Mensaje de log para establecer override manual */
    public static final String MSG_SETTING_MANUAL_OVERRIDE = "Setting manual override strategy: {}";

    /** Mensaje de log para limpiar override manual */
    public static final String MSG_CLEARING_MANUAL_OVERRIDE = "Clearing manual override strategy";

    /** Mensaje de log para cambio de estrategia */
    public static final String MSG_STRATEGY_CHANGE = "Strategy change: {}";

    /** Mensaje de log para error en fuentes de configuración */
    public static final String MSG_ERROR_CONFIG_SOURCES = "Error updating configuration from sources";

    /** Mensaje de log para error recolectando información */
    public static final String MSG_ERROR_COLLECTING_INFO = "Error collecting source information";

    /** Mensaje de log para error leyendo archivo de configuración */
    public static final String MSG_ERROR_READING_CONFIG_FILE = "Error reading strategy from config file: {}";

    /** Mensaje de log para intentar aplicar estrategia */
    public static final String MSG_ATTEMPTING_APPLY_STRATEGY = "Attempting to apply strategy '{}' from source '{}'";

    /** Mensaje de log para estrategia inválida */
    public static final String MSG_INVALID_STRATEGY = "Invalid strategy '{}' from source '{}': {}";

    // =============================================
    // FORMATOS DE MENSAJES
    // =============================================

    /** Formato para mensaje de cambio de estrategia */
    public static final String FORMAT_STRATEGY_CHANGED = "Strategy changed to '%s' from source '%s' (previous source: '%s')";

    /** Formato para mensaje de override limpiado */
    public static final String FORMAT_OVERRIDE_CLEARED = "Manual override cleared, was: %s";

    /** Formato para mensaje de override establecido */
    public static final String FORMAT_OVERRIDE_SET = "Manual override set to: %s";

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