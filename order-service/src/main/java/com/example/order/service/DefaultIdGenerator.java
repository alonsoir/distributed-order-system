package com.example.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct; // Cambiado a jakarta.annotation
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementación robusta del IdGenerator optimizada para sistemas distribuidos de alta escala,
 * con validaciones integradas, monitoreo y detección de colisiones.
 */
@Component
public class DefaultIdGenerator implements IdGenerator {
    private static final Logger log = LoggerFactory.getLogger(DefaultIdGenerator.class);

    // Constantes para la estructura del ID
    private static final String ORDER_ID_KEY = "order:id:counter";
    private static final int NODE_ID_BITS = 10; // Soporta hasta 1024 nodos
    private static final int SEQUENCE_BITS = 12; // Soporta hasta 4096 secuencias por ms
    private static final int MAX_NODE_ID = (1 << NODE_ID_BITS) - 1;

    // Constantes para validación
    private static final int MAX_STRING_ID_LENGTH = 36;
    private static final String CORRELATION_ID_FIELD = "Correlation ID";
    private static final String EVENT_ID_FIELD = "Event ID";
    private static final String EXTERNAL_REF_FIELD = "External reference";

    // Estructura interna
    private final AtomicLong orderIdCounter;
    private final ReactiveRedisTemplate<String, Long> redisTemplate;
    private final int nodeId;
    private final ConcurrentHashMap<String, Boolean> idRegistry;

    // Configuración
    @Value("${app.id.check-collisions:false}")
    private boolean checkCollisions;

    @Value("${app.id.monitoring.enabled:true}")
    private boolean monitoringEnabled;

    @Value("${app.id.redis.fallback-enabled:true}")
    private boolean redisFallbackEnabled;

    /**
     * Constructor principal con inyección de dependencias y validación de parámetros.
     *
     * @param redisTemplate Plantilla reactiva para operaciones con Redis
     * @param nodeId Identificador único del nodo en el sistema distribuido
     */
    @Autowired
    public DefaultIdGenerator(
            ReactiveRedisTemplate<String, Long> redisTemplate,
            @Value("${app.node-id:0}") int nodeId) {
        this.redisTemplate = redisTemplate;

        // Validar y normalizar el nodeId
        if (nodeId < 0) {
            log.warn("Negative node ID provided ({}). Using absolute value.", nodeId);
            nodeId = Math.abs(nodeId);
        }
        if (nodeId > MAX_NODE_ID) {
            log.warn("Node ID exceeds maximum value ({}). Applying modulo to fit within range.", nodeId);
        }
        this.nodeId = nodeId % (1 << NODE_ID_BITS);

        // Inicializar el contador con un valor aleatorio para evitar colisiones iniciales
        this.orderIdCounter = new AtomicLong(ThreadLocalRandom.current().nextLong(1, 1000));

        // Inicializar registro de IDs si se habilita la comprobación de colisiones
        this.idRegistry = checkCollisions ? new ConcurrentHashMap<>() : null;

        log.info("ID Generator initialized with nodeId={}, collision checking={}", this.nodeId, checkCollisions);
    }

    /**
     * Método de inicialización ejecutado tras la construcción del bean.
     * Verifica la conectividad con Redis y muestra mensajes de diagnóstico.
     */
    @PostConstruct
    public void init() {
        // Verificar la conexión a Redis al inicio
        if (monitoringEnabled) {
            redisTemplate.hasKey(ORDER_ID_KEY)
                    .subscribe(
                            exists -> log.info("Redis connectivity check: order ID counter {} exist", exists ? "does" : "does not"),
                            error -> log.error("Failed to connect to Redis during initialization: {}", error.getMessage()),
                            () -> log.debug("Redis connectivity check completed")
                    );
        } else {
            log.info("Monitoring disabled - skipping Redis connectivity check");
        }
    }

    /**
     * Genera un UUID v4 para correlationId, garantizando unicidad global.
     * Incluye validación para asegurar que cumpla con las restricciones de la base de datos.
     *
     * @return Un identificador de correlación único
     * @throws IllegalStateException si el ID generado supera la longitud máxima permitida
     */
    @Override
    public String generateCorrelationId() {
        String correlationId = UUID.randomUUID().toString();
        validateStringId(correlationId, CORRELATION_ID_FIELD);

        if (monitoringEnabled) {
            log.trace("Generated correlation ID: {}", correlationId);
        }

        return correlationId;
    }

    /**
     * Genera un UUID v4 para referencia externa.
     * Incluye validación para asegurar que cumpla con las restricciones de la base de datos.
     *
     * @return Una referencia externa única
     * @throws IllegalStateException si el ID generado supera la longitud máxima permitida
     */
    @Override
    public String generateExternalReference() {
        String externalRef = UUID.randomUUID().toString();
        validateStringId(externalRef, EXTERNAL_REF_FIELD);

        if (monitoringEnabled) {
            log.trace("Generated external reference: {}", externalRef);
        }

        return externalRef;
    }

    /**
     * Genera un UUID v4 para eventId, garantizando unicidad global en entornos distribuidos.
     * Incluye detección de colisiones opcional y validación de longitud.
     *
     * @return Un identificador de evento único
     * @throws IllegalStateException si el ID generado supera la longitud máxima permitida
     */
    @Override
    public String generateEventId() {
        String eventId = UUID.randomUUID().toString();
        validateStringId(eventId, EVENT_ID_FIELD);

        // Verificar colisiones si está habilitado
        if (checkCollisions) {
            if (idRegistry.putIfAbsent(eventId, Boolean.TRUE) != null) {
                log.warn("UUID collision detected for event ID: {}. Generating a new one.", eventId);
                return generateEventId(); // Recursión para generar un nuevo ID
            }

            // Controlar el tamaño del registro de IDs si crece demasiado
            int registrySize = idRegistry.size();
            if (registrySize > 10000 && registrySize % 1000 == 0) {
                log.warn("ID registry size reaching {} entries - consider clearing or disabling collision checking for production", registrySize);
            }
        }

        if (monitoringEnabled) {
            log.trace("Generated event ID: {}", eventId);
        }

        return eventId;
    }

    /**
     * Genera un ID de orden único basado en un algoritmo tipo Snowflake, combinando
     * timestamp, nodeId y secuencia para garantizar unicidad incluso en sistemas distribuidos.
     *
     * La estructura del ID es:
     * - 41 bits: timestamp en milisegundos (soporta ~69 años)
     * - 10 bits: identificador de nodo (hasta 1024 nodos)
     * - 12 bits: secuencia (hasta 4096 IDs por ms por nodo)
     *
     * @return Un ID de orden único de tipo Long
     */
    @Override
    public Long generateOrderId() {
        long timestamp = Instant.now().toEpochMilli();
        long sequence = orderIdCounter.incrementAndGet() & ((1 << SEQUENCE_BITS) - 1);

        // Estructura tipo Snowflake pero sin el bit de signo (63 bits en total)
        Long orderId = ((timestamp << (NODE_ID_BITS + SEQUENCE_BITS)) |
                (nodeId << SEQUENCE_BITS) |
                sequence);

        if (monitoringEnabled && log.isTraceEnabled()) {
            log.trace("Generated order ID: {} (timestamp={}, nodeId={}, sequence={})",
                    orderId, timestamp, nodeId, sequence);
        }

        return orderId;
    }

    /**
     * Genera IDs de orden centralizados usando Redis cuando se requiere estricta
     * secuencialidad global. Incluye manejo robusto de errores y fallback automático.
     *
     * @return Un Mono que emite un ID de orden único basado en un contador centralizado en Redis
     */
    public Mono<Long> generateOrderIdWithRedis() {
        return redisTemplate.opsForValue().increment(ORDER_ID_KEY)
                .defaultIfEmpty(1L)  // En caso de que la clave no exista inicialmente
                .doOnNext(id -> {
                    if (monitoringEnabled) {
                        log.trace("Generated Redis-based order ID: {}", id);

                        // Alerta temprana si el contador se acerca a límites peligrosos
                        if (id > Long.MAX_VALUE / 2) {
                            log.warn("Redis order ID counter reaching critical level: {} (half of max Long value)", id);
                        }
                    }
                })
                .onErrorResume(e -> {
                    if (redisFallbackEnabled) {
                        Long fallbackId = generateOrderId();
                        log.error("Error generating Redis ID: {}. Falling back to local Snowflake ID: {}",
                                e.getMessage(), fallbackId);
                        return Mono.just(fallbackId);
                    } else {
                        log.error("Error generating Redis ID and fallback is disabled: {}", e.getMessage());
                        return Mono.error(e);  // Propagar el error si no se permite fallback
                    }
                });
    }

    /**
     * Método privado para validación robusta de IDs de tipo String.
     *
     * @param id El ID a validar
     * @param fieldName Nombre del campo para mensajes de error
     * @throws IllegalStateException si el ID es nulo o excede la longitud máxima
     */
    private void validateStringId(String id, String fieldName) {
        if (id == null) {
            String errorMsg = fieldName + " cannot be null";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (id.length() > MAX_STRING_ID_LENGTH) {
            String errorMsg = String.format(
                    "%s length exceeds maximum of %d characters (generated value: %s, length: %d)",
                    fieldName, MAX_STRING_ID_LENGTH, id, id.length()
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }
    }

    /**
     * Limpia el registro de IDs usado para detección de colisiones.
     * Útil para tests o para liberar memoria en ejecuciones largas.
     */
    public void clearIdRegistry() {
        if (idRegistry != null) {
            int size = idRegistry.size();
            idRegistry.clear();
            log.info("Cleared ID registry containing {} entries", size);
        } else {
            log.debug("ID registry clearing requested, but collision checking is disabled");
        }
    }

    /**
     * Método utilitario para extraer el timestamp de un ID de orden generado.
     * Útil para depuración y análisis.
     *
     * @param orderId El ID a analizar
     * @return La marca de tiempo extraída del ID
     */
    public long extractTimestampFromOrderId(long orderId) {
        return orderId >> (NODE_ID_BITS + SEQUENCE_BITS);
    }

    /**
     * Método utilitario para extraer el nodeId de un ID de orden generado.
     * Útil para depuración y análisis.
     *
     * @param orderId El ID a analizar
     * @return El ID de nodo extraído del ID
     */
    public int extractNodeIdFromOrderId(long orderId) {
        return (int)((orderId >> SEQUENCE_BITS) & MAX_NODE_ID);
    }

    /**
     * Método utilitario para extraer la secuencia de un ID de orden generado.
     * Útil para depuración y análisis.
     *
     * @param orderId El ID a analizar
     * @return El número de secuencia extraído del ID
     */
    public int extractSequenceFromOrderId(long orderId) {
        return (int)(orderId & ((1 << SEQUENCE_BITS) - 1));
    }

    /**
     * Comprueba la salud del sistema de generación de IDs.
     * Útil para endpoints de health check.
     *
     * @return Un Mono que emite true si el sistema está en buen estado, false en caso contrario
     */
    public Mono<Boolean> checkHealth() {
        // Verificar componentes críticos
        return redisTemplate.hasKey(ORDER_ID_KEY)
                .map(exists -> {
                    log.debug("Health check - Redis connectivity: OK, counter exists: {}", exists);
                    return true;
                })
                .onErrorResume(e -> {
                    log.warn("Health check - Redis connectivity issue: {}", e.getMessage());
                    return Mono.just(redisFallbackEnabled); // Si hay fallback, sigue siendo saludable
                });
    }
}