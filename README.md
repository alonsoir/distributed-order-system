# Order Processing Service / Servicio de Procesamiento de Órdenes

## English

### Overview
The **Order Processing Service** is a robust, reactive microservice designed to handle order creation and processing using a saga pattern. Built with Spring WebFlux, R2DBC, and Redis, it ensures reliable, fault-tolerant, and scalable order management. The service is engineered to address both logical (e.g., validation, consistency) and physical (e.g., network failures, database issues) challenges, aiming to deliver a production-ready, state-of-the-art solution.

This project is a reference implementation for distributed systems, showcasing best practices in reactive programming, error handling, monitoring, and testing. It is designed to be maintainable, extensible, and easy to understand for engineers joining the project.

### Goals
- **Robustness**: Handle hardware failures (e.g., Redis or database outages) and logical errors (e.g., invalid inputs) gracefully using circuit breakers, retries, and compensations.
- **Single Responsibility Principle (SRP)**: Ensure classes and methods have clear, focused responsibilities through modular design and upcoming refactoring.
- **Testability**: Achieve comprehensive test coverage with unit, integration, and end-to-end tests to validate all scenarios, including failure cases.
- **Scalability**: Prepare the system for high concurrency and future enhancements, such as stored procedures for database interactions.
- **State-of-the-Art Code**: Deliver a solution that serves as a gold standard for other engineers, addressing real-world challenges with clarity and elegance.

### Features
- **Saga Pattern**: Orchestrates order creation and stock reservation as a distributed transaction, ensuring consistency across services.
- **Event-Driven Architecture**: Publishes events to Redis streams for asynchronous processing, with fallback to an outbox table in case of Redis failures.
- **Fault Tolerance**:
    - Circuit breakers for external service calls (e.g., Redis, inventory service).
    - Retries for transient failures in Redis and outbox persistence.
    - Compensations for failed saga steps (e.g., releasing reserved stock).
- **Monitoring**: Tracks success/failure metrics and latency using Micrometer.
- **Transactional Integrity**: Uses Spring's `TransactionalOperator` for atomic database operations.
- **Validation**: Enforces strict input validation to prevent invalid states.

### Project Structure
- **Source Code**:
    - `src/main/java/com/example/order/service/OrderService.java`: Core service implementing the saga pattern, event publishing, and error handling.
    - `src/main/java/com/example/order/domain/Order.java`: Domain model for orders.
- **Tests**:
    - `src/test/java/com/example/order/service/OrderServiceUnitTest.java`: Unit tests covering happy paths, failure scenarios, and validations.
    - (Planned) Integration tests using Testcontainers for MySQL and Redis.
    - (Planned) End-to-end tests simulating real-world failures (e.g., Redis outage, network timeouts).
- **Database Schema**:
    - `schema.sql`: Defines tables for orders, outbox, and processed events.

### Current Status
- **Unit Tests**: Comprehensive unit tests are implemented, covering:
    - Successful order processing.
    - Failure scenarios (e.g., circuit breaker open, Redis failure, compensation failures).
    - Input validations (e.g., invalid order IDs, null event types).
    - Ongoing: Adding more validation tests and edge cases.
- **Code Corrections**: Recent fixes addressed compilation issues in `OrderEvent` records and `SagaStep` constructor.
- **Next Steps**:
    1. Validate unit tests to ensure all are passing.
    2. Add more unit tests for edge cases and validations.
    3. Refactor `OrderService` to adhere to SRP, introducing:
        - `AbstractOrderSaga` for saga orchestration.
        - `EventPublisher` for event publishing.
        - `OrderRepository` for database operations.
    4. Implement integration tests with Testcontainers.
    5. Develop end-to-end tests for real-world failure scenarios.
    6. Introduce stored procedures for database operations as the final step.
    7. Tag the polished version as `0.0.1-RELEASE`.

### How to Run
#### Prerequisites
- Java 17+
- Maven 3.8+
- MySQL 8+ (for integration tests)
- Redis 7+ (for event streaming)
- Docker (for Testcontainers)

#### Setup
1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd order-processing-service
   
2. Configure MySQL and Redis:
Update application.properties with your MySQL and Redis connection details:
properties
```
spring.r2dbc.url=r2dbc:mysql://localhost:3306/orders_db
spring.r2dbc.username=root
spring.r2dbc.password=your_password
spring.redis.host=localhost
spring.redis.port=6379
```
Alternatively, use Testcontainers for integration tests (no local setup required).

Initialize the MySQL database:
Run the schema.sql script to create the necessary tables (orders, outbox, processed_events).

Build and run tests:
```
bash

mvn clean test
```
Run the application:
```
bash

mvn spring-boot:run
```
3. Future Improvements
Refactoring:

1) Split OrderService into smaller, focused components to improve maintainability and testability.

2) Stored Procedures: Migrate database interactions to stored procedures for atomicity and performance.

3) Advanced Monitoring: Add distributed tracing (e.g., OpenTelemetry) for better observability.

4) Concurrency Testing: Validate behavior under high load with tools like Gatling or JMeter.

5) Create docker images for MySQL and Redis/Kafka.

Contributing

We welcome contributions! Please follow these steps:
Fork the repository.

Create a feature branch (git checkout -b feature/my-feature).

Commit your changes (git commit -m "Add my feature").

Push to the branch (git push origin feature/my-feature).

Open a pull request.

License
This project is licensed under the MIT License. See the LICENSE file for details.
Contact
For questions or feedback, please contact the maintainers at your-email@example.com (mailto:your-email@example.com).

### Español

### Resumen

El Servicio de Procesamiento de Órdenes es un microservicio reactivo y robusto diseñado para gestionar la creación y procesamiento de órdenes utilizando un patrón de saga. Construido con Spring WebFlux, R2DBC y Redis, garantiza una gestión de órdenes confiable, tolerante a fallos y escalable. El servicio está diseñado para abordar desafíos lógicos (por ejemplo, validaciones, consistencia) y físicos (por ejemplo, fallos de red, problemas de base de datos), con el objetivo de ofrecer una solución lista para producción y de vanguardia.
Este proyecto es una implementación de referencia para sistemas distribuidos, mostrando las mejores prácticas en programación reactiva, manejo de errores, monitoreo y pruebas. Está diseñado para ser mantenible, extensible y fácil de entender para los ingenieros que se unan al proyecto.
Objetivos
Robustez: Manejar fallos de hardware (por ejemplo, caídas de Redis o base de datos) y errores lógicos (por ejemplo, entradas inválidas) de forma elegante usando circuit breakers, reintentos y compensaciones.

Principio de Responsabilidad Única (SRP): Asegurar que las clases y métodos tengan responsabilidades claras y enfocadas mediante un diseño modular y una refactorización próxima.

Testabilidad: Lograr una cobertura completa de pruebas con tests unitarios, de integración y end-to-end para validar todos los escenarios, incluyendo casos de fallo.

Escalabilidad: Preparar el sistema para alta concurrencia y mejoras futuras, como procedimientos almacenados para interacciones con la base de datos.

Código de Vanguardia: Entregar una solución que sirva como estándar de excelencia para otros ingenieros, abordando desafíos del mundo real con claridad y elegancia.

Características
Patrón de Saga: Orquesta la creación de órdenes y la reserva de inventario como una transacción distribuida, asegurando consistencia entre servicios.

Arquitectura Basada en Eventos: Publica eventos en streams de Redis para procesamiento asíncrono, con fallback a una tabla de outbox en caso de fallos de Redis.

Tolerancia a Fallos:
Circuit breakers para llamadas a servicios externos (por ejemplo, Redis, servicio de inventario).

Reintentos para fallos transitorios en Redis y persistencia en outbox.

Compensaciones para pasos de saga fallidos (por ejemplo, liberación de inventario reservado).

Monitoreo: Registra métricas de éxito/fallo y latencia usando Micrometer.

Integridad Transaccional: Usa TransactionalOperator de Spring para operaciones atómicas en la base de datos.

Validación: Aplica validaciones estrictas para prevenir estados inválidos.

Estructura del Proyecto
Código Fuente:
```
src/main/java/com/example/order/service/OrderService.java: Servicio principal que implementa el patrón de saga, publicación de eventos y manejo de errores.

src/main/java/com/example/order/domain/Order.java: Modelo de dominio para órdenes.
```
Pruebas:
```
src/test/java/com/example/order/service/OrderServiceUnitTest.java: Tests unitarios que cubren casos exitosos, escenarios de fallo y validaciones.
```
(Planeado) Tests de integración usando Testcontainers para MySQL y Redis.

(Planeado) Tests end-to-end que simulen fallos del mundo real (por ejemplo, caída de Redis, timeouts de red).

### Esquema de Base de Datos:
schema.sql: Define las tablas para órdenes, outbox y eventos procesados.

### Estado Actual
Tests Unitarios: Se han implementado tests unitarios completos que cubren:
Procesamiento exitoso de órdenes.

Escenarios de fallo (por ejemplo, circuit breaker abierto, fallo de Redis, compensaciones fallidas).

Validaciones de entrada (por ejemplo, IDs de orden inválidos, tipos de evento nulos).

En curso: Añadir más tests de validación y casos verdaderamente extremos.

Correcciones de Código: Se resolvieron recientemente problemas de compilación en los records OrderEvent y el constructor de SagaStep.

### Próximos Pasos:

Validar los tests unitarios para asegurar que todos pasan.

Añadir más tests unitarios para casos extremos y validaciones.

Refactorizar OrderService para cumplir con SRP, introduciendo:
AbstractOrderSaga para la orquestación de sagas.

EventPublisher para la publicación de eventos.

OrderRepository para operaciones de base de datos.

Implementar tests de integración con Testcontainers.

Desarrollar tests end-to-end para escenarios de fallo del mundo real.

Introducir procedimientos almacenados para operaciones de base de datos como paso final.

Etiquetar la versión pulida como 0.0.1-RELEASE.

### Cómo Ejecutar
Prerrequisitos
Java 17+, aunque yo he probado con Java 21 y 23.

Maven 3.8+

MySQL 8+ (para tests de integración)

Redis 7+ (para streaming de eventos)

Docker (para Testcontainers)

### Configuración
Clona el repositorio:
bash
```
git clone <url-del-repositorio>
cd servicio-procesamiento-ordenes
```
### Configura MySQL y Redis:
Actualiza application.properties con los detalles de conexión a MySQL y Redis:
properties
```
spring.r2dbc.url=r2dbc:mysql://localhost:3306/orders_db
spring.r2dbc.username=root
spring.r2dbc.password=tu_contraseña
spring.redis.host=localhost
spring.redis.port=6379
```

Alternativamente, usa Testcontainers para tests de integración (no requiere configuración local).
Yo uso Docker Desktop para levantar los contenedores. He probado a usar Finch, pero hay problemas
con test containers actualmente.

### Inicializa la base de datos MySQL:
Ejecuta el script schema.sql para crear las tablas necesarias (orders, outbox, processed_events).

Construye y ejecuta los tests:
```
mvn clean test
```

Ejecuta la aplicación:
```
mvn spring-boot:run
```
Mejoras Futuras
Refactorización: Dividir OrderService en componentes más pequeños y enfocados para mejorar la mantenibilidad y testabilidad.

Procedimientos Almacenados: Migrar las interacciones con la base de datos a procedimientos almacenados para atomicidad y rendimiento.

Monitoreo Avanzado: Añadir trazabilidad distribuida (por ejemplo, OpenTelemetry) para mejor observabilidad.

Pruebas de Concurrencia: Validar el comportamiento bajo alta carga con herramientas como Gatling o JMeter.

### Contribuir
¡Agradecemos las contribuciones! Sigue estos pasos:
Haz un fork del repositorio.

Crea una rama para tu funcionalidad (git checkout -b feature/mi-funcionalidad).

Confirma tus cambios (git commit -m "Añadir mi funcionalidad").

Sube la rama (git push origin feature/mi-funcionalidad).

Abre un pull request.

Licencia
Este proyecto está licenciado bajo la Licencia MIT. Consulta el archivo LICENSE para más detalles.
Contacto
Para preguntas o feedback, contacta a los mantenedores en alonsoir@gmail.com (mailto:alonsoir@gmail.com).

---

### Plan para avanzar

1) Ejecutar tests unitarios:
Aplica el código corregido de OrderService y los tests actualizados de OrderServiceUnitTest (proporcionados en respuestas anteriores).

Ejecuta mvn clean test para confirmar que todos los tests pasan.

### Añadir más tests unitarios:
Implementar los tests adicionales propuestos:
shouldFailToProcessOrderWithInvalidOrderId

shouldFailToPublishEventWithNullType

shouldHandleOnTimeoutCorrectly

Añade otros casos de validación (por ejemplo, más pruebas para SagaStep con campos inválidos).

### Refactorización:
Esboza AbstractOrderSaga, EventPublisher, y OrderRepository una vez que los tests unitarios estén en verde.

Ajusta los tests para la nueva estructura.

### Tests de integración:
Configura Testcontainers para MySQL y Redis.

Usa el schema.sql adaptado para inicializar la base de datos.

Diseña tests que validen interacciones reales con MySQL y Redis.

### Tests end-to-end:
Simula escenarios de fallo (Redis caído, timeouts, compensaciones fallidas) usando Testcontainers o WireMock.

### Procedimientos almacenados:
Diseña procedimientos para createOrder, executeStep, y updateOrderStatus en MySQL.

Implementa cuando la versión refactorizada esté pulida.

Etiqueta como 0.0.1-RELEASE.

