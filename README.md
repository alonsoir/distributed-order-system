# Order Processing Service (Sistema de Procesamiento de Órdenes)

## Overview / Descripción General

The Order Processing Service is a robust, reactive microservice designed to handle order creation and processing using 
a saga pattern. Built with Spring WebFlux, R2DBC, and Redis, it ensures reliable, fault-tolerant, and scalable order 
management in distributed environments.

El Servicio de Procesamiento de Órdenes es un microservicio reactivo y robusto diseñado para gestionar la creación y 
procesamiento de órdenes utilizando un patrón de saga, construido con Spring WebFlux, R2DBC y Redis para garantizar una 
gestión fiable, tolerante a fallos y escalable en entornos distribuidos.

## Architecture / Arquitectura

This service implements a publisher (PUB) component of a larger PUB/SUB reactive asynchronous system. 
The architecture emphasizes resilience against physical failures through:

Este servicio implementa el componente publicador (PUB) de un sistema reactivo asíncrono PUB/SUB más amplio. 
La arquitectura enfatiza la resiliencia ante fallos físicos mediante:

- **Saga Pattern / Patrón Saga**: Orchestrates distributed transactions with compensation mechanisms 
/ Orquesta transacciones distribuidas con mecanismos de compensación
- **Event-Driven Design / Diseño Orientado a Eventos**: Uses Redis streams with outbox pattern as fallback 
/ Utiliza streams de Redis con el patrón outbox como respaldo
- **Layered Architecture / Arquitectura en Capas**:
    - `OrderService`: High-level facade with circuit breaker patterns / Fachada de alto nivel con patrones de circuit 
  breaker
    - `SagaOrchestrator`: Core component managing the saga execution flow / Componente central que gestiona el flujo de 
  ejecución de la saga
    - `CompensationManager`: Handles compensating transactions for failed steps / Gestiona transacciones de compensación
  para pasos fallidos
    - `EventPublisher`: Manages event publication with fallback mechanisms 
  / Administra la publicación de eventos con mecanismos de respaldo.

## Transicion de estados entre pasos que puedan ocurrir en el flujo de trabajo.

Muy relacionado con el flujo de trabajo, pero no es el flujo de trabajo en sí.

2. Flujo Normal:
   ORDER_CREATED → ORDER_VALIDATED → PAYMENT_PENDING → PAYMENT_PROCESSING → PAYMENT_CONFIRMED →
   STOCK_CHECKING → STOCK_RESERVED → ORDER_PROCESSING → ORDER_PREPARED →
   SHIPPING_PENDING → SHIPPING_ASSIGNED → SHIPPING_IN_PROGRESS → DELIVERED_TO_COURIER →
   OUT_FOR_DELIVERY → DELIVERED → PENDING_CONFIRMATION → RECEIVED_CONFIRMED → ORDER_COMPLETED

2. Flujo de Fallos de Pago:
   PAYMENT_PENDING → PAYMENT_PROCESSING → PAYMENT_DECLINED →
   [PAYMENT_PENDING (reintento) o ORDER_FAILED/ORDER_CANCELED]

3. Flujo de Fallos de Inventario:
   STOCK_CHECKING → STOCK_UNAVAILABLE →
   [STOCK_CHECKING (reintento) o ORDER_FAILED/ORDER_CANCELED]

4. Flujo de Excepciones Técnicas:
   [Cualquier Estado] → TECHNICAL_EXCEPTION → WAITING_RETRY → [Estado Original] o
   TECHNICAL_EXCEPTION → MANUAL_REVIEW → [Decisión Manual]

5. Flujo de Devolución:
   ORDER_COMPLETED → RETURN_REQUESTED → RETURN_APPROVED → RETURN_IN_TRANSIT →
   RETURN_RECEIVED → REFUND_PROCESSING → REFUND_COMPLETED

6. Flujo de Fallos de Entrega:
   OUT_FOR_DELIVERY → DELIVERY_ATTEMPTED → [OUT_FOR_DELIVERY (reintento) o DELIVERY_EXCEPTION] →
   [ORDER_FAILED o MANUAL_REVIEW]

## Key Features / Características Principales

### Resilience / Resiliencia
- **Circuit Breakers / Cortocircuitos**: Per-step isolation of failures / Aislamiento de fallos por paso
- **Bulkheads / Mamparos**: Resource isolation for concurrent executions 
/ Aislamiento de recursos para ejecuciones concurrentes
- **Retry Mechanisms / Mecanismos de Reintento**: For transient failures with back-off strategies 
/ Para fallos transitorios con estrategias de retroceso
- **Compensations / Compensaciones**: Rolling back partial transactions / Reversión de transacciones parciales
- **Event Outbox / Buzón de Eventos**: Ensures event delivery even during Redis outages / Garantiza la 
entrega de eventos incluso durante caídas de Redis

### Observability / Observabilidad
- **Enhanced Diagnostics / Diagnósticos Mejorados**: Rich context using MDC for correlated logging / 
Contexto enriquecido usando MDC para logging correlacionado
- **Detailed Metrics / Métricas Detalladas**: Latency, success/failure rates, retries per operation / Latencia, 
tasas de éxito/fallo, reintentos por operación
- **Granular Performance Monitoring / Monitoreo de Rendimiento Granular**: Per-step timers and counters / Temporizadores 
y contadores por paso

### Code Quality / Calidad de Código
- **Single Responsibility Principle / Principio de Responsabilidad Única**: Each component with clear, focused responsibilities 
/ Cada componente con responsabilidades claras y enfocadas
- **Interface-Based Design / Diseño Basado en Interfaces**: Clean separation between contracts and implementations 
/ Separación limpia entre contratos e implementaciones
**Comprehensive Testing / Pruebas Exhaustivas**: Unit and integration tests covering happy paths and failure scenarios
/ Pruebas unitarias y de integración que cubren escenarios exitosos y de fallo
- **Diagnostic-First Approach / Enfoque Diagnóstico Primero**: Designed for easy troubleshooting in production / 
- Diseñado para facilitar la solución de problemas en producción

## Components / Componentes

### Core Interfaces / Interfaces Principales
- `OrderService`: Facade for order processing operations / Fachada para operaciones de procesamiento de órdenes
- `SagaOrchestrator`: Manages multi-step transactional flows / Gestiona flujos transaccionales de múltiples pasos
- `CompensationManager`: Handles failure recovery / Maneja la recuperación de fallos
- `EventPublisher`: Reliable event delivery with fallbacks / Entrega confiable de eventos con sistemas de respaldo

### Implementation Details / Detalles de Implementación
The system follows an event-sourcing approach where:

El sistema sigue un enfoque de event-sourcing donde:

1. Orders are created in a pending state / Las órdenes se crean en estado pendiente
2. A saga orchestrates necessary steps (e.g., inventory reservation) / Una saga orquesta los pasos necesarios 
(ej., reserva de inventario)
3. Events are published for each step / Se publican eventos para cada paso
4. Compensations are triggered on failures / Las compensaciones se activan en caso de fallos
5. The state is updated accordingly (completed/failed) / El estado se actualiza en consecuencia (completado/fallido)

## Current Status / Estado Actual

We have successfully implemented:

Hemos implementado con éxito:

- ✅ Core domain models and interfaces / Modelos de dominio e interfaces principales
- ✅ Saga orchestration with step execution / Orquestación de saga con ejecución por pasos
- ✅ Compensation mechanism for failed steps / Mecanismo de compensación para pasos fallidos
- ✅ Enhanced diagnostic context for troubleshooting / Contexto de diagnóstico mejorado para solución de problemas
- ✅ Circuit breaker and bulkhead patterns / Patrones de circuit breaker y bulkhead
- ✅ Event publishing with outbox pattern / Publicación de eventos con patrón outbox
- ✅ Unit tests for core components / Pruebas unitarias para componentes principales

Next steps:

Próximos pasos:

- 🔄 SonarQube issue resolution / Resolución de problemas identificados por SonarQube
- 🔄 Integration tests with real Redis and MySQL / Pruebas de integración con Redis y MySQL reales
- 🔄 Performance benchmarking and optimization / Evaluación y optimización de rendimiento
- 🔄 Documentation improvements / Mejoras en la documentación

## How to Run / Cómo Ejecutar

### Prerequisites / Prerrequisitos
- Java 17+ (tested with Java 21 and 23) / Java 17+ (probado con Java 21 y 23)
- Maven 3.8+ / Maven 3.8+
- Docker Desktop (for running containers) / Docker Desktop (para ejecutar contenedores)
- MySQL 8+ and Redis 7+ (or use Testcontainers) / MySQL 8+ y Redis 7+ (o usar Testcontainers)

### Setup / Configuración
```bash
# Clone the repository / Clonar el repositorio
git clone https://github.com/yourusername/distributed-order-system.git
cd distributed-order-system

# Build the project / Compilar el proyecto
mvn clean install

# Run tests / Ejecutar pruebas
mvn test

# Run SonarQube analysis / Ejecutar análisis de SonarQube
mvn clean verify sonar:sonar -Dsonar.token=YOUR_SONAR_TOKEN

# Run the application / Ejecutar la aplicación
mvn spring-boot:run

# Configuration / Configuración
The application uses different configuration profiles:
La aplicación utiliza diferentes perfiles de configuración:
Main Configuration (application.yml) / Configuración Principal
'''
  r2dbc:
    url: ${SPRING_R2DBC_URL:r2dbc:mysql://localhost:3306/orders}
    username: ${SPRING_R2DBC_USERNAME:root}
    password: ${SPRING_R2DBC_PASSWORD:root}
  data:
    redis:
      host: ${SPRING_REDIS_HOST:localhost}
      port: ${SPRING_REDIS_PORT:6379}
  sql:
    init:
      mode: ${SPRING_SQL_INIT_MODE:never}

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
'''

# Additional configuration for Redis, outbox, DLQ and more...
# Configuración adicional para Redis, outbox, DLQ y más...
Production Profile (application-prod.yml) / Perfil de Producción
Contains optimized settings for production environments with enhanced monitoring.
Contiene configuraciones optimizadas para entornos de producción con monitoreo mejorado.
Test Profiles / Perfiles de Prueba

application-integration.yml: Settings for integration tests / Configuración para pruebas de integración
application-unit.yml: Settings for unit tests with in-memory database / Configuración para pruebas unitarias con base de
 datos en memoria
application-stress.yml: Configuration for stress testing / Configuración para pruebas de estrés

Activate profiles using spring.profiles.active=prod (environment variable or command line).
Active los perfiles usando spring.profiles.active=prod (variable de entorno o línea de comandos).
Generaré un un script para ejecutar el proyecto con los perfiles activos.

# Database Schema / Esquema de Base de Datos

The service uses the following main tables:
El servicio utiliza las siguientes tablas principales:

    orders: Stores order data with status tracking / Almacena datos de órdenes con seguimiento de estado
    outbox: Fallback for event publishing during Redis failures / Respaldo para publicación de eventos durante fallos de 
    Redis
    processed_events: Tracks processed events for idempotency / Rastrea eventos procesados para idempotencia

# Development Principles / Principios de Desarrollo

Robustness First / Robustez Primero: Design assuming hardware and network failures will occur / Diseñar asumiendo que 
ocurrirán fallos de hardware y red
Diagnostic Excellence / Excelencia en Diagnóstico: Every failure should be easily traceable and understood / 
Cada fallo debe ser fácilmente rastreable y comprensible
Defense in Depth / Defensa en Profundidad: Multiple fallback mechanisms for critical operations / Múltiples mecanismos 
de respaldo para operaciones críticas
Clean Code / Código Limpio: Clear responsibilities, meaningful names, comprehensive documentation / Responsabilidades 
claras, nombres significativos, documentación completa
Testability / Testabilidad: Every component designed to be easily testable in isolation / Cada componente diseñado para 
ser fácilmente testeable de forma aislada

# Deployment Options / Opciones de Despliegue
The system can be deployed with:
El sistema puede desplegarse con:

Shared Database / Base de Datos Compartida: Both PUB and SUB components use the same MySQL instance / Ambos componentes 
PUB y SUB utilizan la misma instancia de MySQL
Dedicated Databases / Bases de Datos Dedicadas: Each component has its own database for isolation / Cada componente 
tiene su propia base de datos para aislamiento

Both approaches are supported, with the choice depending on:
Ambos enfoques son compatibles, con la elección dependiendo de:

Required level of isolation / Nivel requerido de aislamiento
Data volume and scalability needs / Volumen de datos y necesidades de escalabilidad
Operational complexity tolerance / Tolerancia a la complejidad operativa

# Future Improvements / Mejoras Futuras

Subscriber (SUB) component implementation / Implementación del componente suscriptor (SUB)
Extended metrics and monitoring dashboards / Métricas extendidas y paneles de monitoreo
Performance optimization for high-throughput scenarios / Optimización de rendimiento para escenarios de alto throughput
Stored procedures for database operations / Procedimientos almacenados para operaciones de base de datos
Multi-region deployment support / Soporte para despliegue multi-región

# License / Licencia
This project is licensed under the MIT License - see the LICENSE file for details.
Este proyecto está licenciado bajo la Licencia MIT - consulte el archivo LICENSE para más detalles.

# Contact / Contacto
For questions or feedback, please contact the maintainers at alonsoir@gmail.com.
Para preguntas o comentarios, contacte a los mantenedores en alonsoir@gmail.com.

## Resumen de la Refactorización
He creado las siguientes clases y documentación:

SagaOrchestratorAtMostOnceImplV2: Nueva implementación del orquestador de sagas que utiliza exclusivamente 
EventRepository y elimina la dependencia directa de DatabaseClient.
Clases marcadas como @Deprecated:

SagaOrchestrator (interfaz)
RobustBaseSagaOrchestrator (clase base original)
SagaOrchestratorAtMostOnceImpl2 (implementación original)


Documentación:

JavaDoc detallado para la nueva implementación
README con información sobre la migración
Guía detallada paso a paso para la migración
Diagrama de arquitectura para visualizar los cambios



Principales Mejoras

Mejor Separación de Responsabilidades:

Se elimina la dependencia directa de DatabaseClient en las clases de negocio
La lógica de acceso a datos se encapsula completamente en EventRepository


Mayor Cohesión:

Las clases de negocio ahora se centran exclusivamente en la lógica de orquestación
La persistencia está completamente abstraída a través de EventRepository


Facilidad de Pruebas:

Es más sencillo crear mocks de EventRepository que de DatabaseClient
Las pruebas unitarias son más claras y enfocadas


Mejor Manejo de Errores:

Se mantiene la clasificación robusta de errores
Se mejora la trazabilidad y registro de eventos para auditoría



Pasos a Seguir
Para los desarrolladores que utilizan estas clases, se recomienda:

Migrar a las nuevas implementaciones V2 lo antes posible
Actualizar cualquier extensión de las clases base para utilizar las nuevas versiones
Adaptar las pruebas unitarias para trabajar con las nuevas clases
Revisar y adaptar cualquier código personalizado que interactúe con estas clases

Las clases marcadas como obsoletas (@Deprecated) se mantendrán por un tiempo para facilitar la transición, pero 
eventualmente serán eliminadas en futuras versiones.

# Refactorización de Tests para SagaOrchestratorAtLeastOnceImplV2

## Introducción

Este documento describe la refactorización realizada en los tests para `SagaOrchestratorAtLeastOnceImplV2`, la nueva implementación que utiliza exclusivamente `EventRepository` en lugar de `DatabaseClient`. La refactorización de los tests es un paso crucial para asegurar que la nueva implementación funciona correctamente.

## Tests Creados

Se han creado dos nuevos tests:

1. **SagaOrchestratorAtLeastOnceV2UnitTest**:
   - Reemplaza al obsoleto `SagaOrchestratorAtLeastOnceUnitTest`
   - Pruebas unitarias básicas de funcionalidad
   - Similar en estructura al test anterior, pero adaptado para usar `EventRepository`

2. **SagaOrchestratorAtLeastOnceV2EventRepositoryTest**:
   - Test especializado para verificar la integración con `EventRepository`
   - Prueba en detalle las interacciones con los métodos de `EventRepository`
   - Verifica comportamientos idempotentes y de manejo de errores

## Principales Diferencias con el Test Antiguo

### 1. Refactorización de Mocks

**Test Antiguo**:
```java
@Mock
private DatabaseClient databaseClient;

@Mock
private DatabaseClient.GenericExecuteSpec executeSpec;

// ... otros mocks

// Configuración de DatabaseClient
when(databaseClient.sql(anyString())).thenReturn(executeSpec);
when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
when(executeSpec.then()).thenReturn(Mono.empty());
```

**Test Nuevo**:
```java
  @Mock
  private EventRepository eventRepository;
  
  // ... otros mocks
  
  // Configuración de EventRepository
  when(eventRepository.isEventProcessed(anyString()))
          .thenReturn(Mono.just(false));
  when(eventRepository.markEventAsProcessed(anyString()))
          .thenReturn(Mono.empty());
  when(eventRepository.saveOrderData(anyLong(), anyString(), anyString(), any(OrderEvent.class)))
          .thenReturn(Mono.empty());
  // ... más configuraciones específicas
```

### 2. Verificación de Interacciones

**Test Antiguo**:
```java
// Verificar SQL y bindings (frágil y acoplado a la implementación)
verify(databaseClient, times(3)).sql(anyString());
verify(executeSpec, times(5)).bind(anyString(), any());
```

**Test Nuevo**:
```java
// Verificar interacciones con métodos de alto nivel (más robusto)
verify(eventRepository).saveOrderData(eq(ORDER_ID), eq(CORRELATION_ID), eq(EVENT_ID), any());
verify(eventRepository, atLeastOnce()).saveEventHistory(
        anyString(), anyString(), anyLong(), anyString(), anyString(), anyString());
```

### 3. Nuevas Pruebas Específicas

El nuevo test `SagaOrchestratorAtLeastOnceV2EventRepositoryTest` incluye pruebas específicas para:

- Comportamiento idempotente
- Manejo de errores y compensación
- Registro de historial de eventos
- Bloqueo/desbloqueo de recursos
- Soporte para diferentes modos de entrega
- Verificación de eventos ya procesados

Estas pruebas no estaban presentes en el test antiguo y proporcionan una cobertura más completa.

## Ventajas de los Nuevos Tests

1. **Mayor abstracción**:
  - Los tests no dependen de detalles de implementación SQL
  - Menos frágiles ante cambios en la implementación de persistencia

2. **Mejor legibilidad**:
  - Los métodos de `EventRepository` expresan claramente la intención
  - Las verificaciones son más semánticas y menos técnicas

3. **Mejor cobertura de casos de uso**:
  - Pruebas específicas para idempotencia
  - Pruebas para diferentes modos de entrega
  - Mejor cobertura de caminos de error

4. **Facilidad de mantenimiento**:
  - Cambios en la implementación de `EventRepository` no afectan a los tests
  - Más fácil de adaptar a futuros cambios en el dominio

5. **Independencia de tecnología**:
  - Los tests no están acoplados a R2DBC/DatabaseClient
  - Soporte para diferentes implementaciones de `EventRepository`

## Conclusión

La refactorización de los tests no solo se alinea con los cambios en el código de producción, sino que también mejora 
significativamente la calidad y robustez de las pruebas. Los nuevos tests son más expresivos, menos frágiles y 
proporcionan mejor cobertura, facilitando el mantenimiento futuro y la evolución del sistema.