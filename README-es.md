# Servicio de Procesamiento de Órdenes

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-green.svg)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-7+-red.svg)](https://redis.io/)
[![MySQL](https://img.shields.io/badge/MySQL-8+-blue.svg)](https://www.mysql.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Un microservicio reactivo y robusto para procesamiento de órdenes utilizando el patrón Saga, construido con Spring 
WebFlux, R2DBC y Redis. Diseñado para gestión escalable y tolerante a fallos en entornos distribuidos.

> **Nota**: Este proyecto fue desarrollado mediante programación colaborativa humano-IA utilizando Claude Sonnet, 
> representando un enfoque innovador de desarrollo de software que combina la experiencia y criterio humano con la 
> capacidad de generación rápida de código y optimización de la IA.

## 🚀 Inicio Rápido

### Prerrequisitos
- Java 17+ (probado con Java 21 y 23)
- Maven 3.8+
- Docker Desktop
- MySQL 8+ y Redis 7+ (o usar TestContainers)

### Instalación y Ejecución

```bash
# Clonar repositorio
git clone https://github.com/yourusername/distributed-order-system.git
cd distributed-order-system

# Compilar proyecto
mvn clean install

# Ejecutar pruebas
mvn test

# Iniciar aplicación
mvn spring-boot:run
```

### Configuración con Docker
```bash
# Iniciar dependencias
docker-compose up -d mysql redis

# Ejecutar aplicación
./scripts/run-local.sh
```

## 🏗️ Visión General de la Arquitectura

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   API Órdenes   │───▶│ Orquestador     │───▶│ Publicador de   │
│                 │    │    Saga         │    │    Eventos      │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   MySQL R2DBC   │    │ Gestor de       │    │  Redis Streams  │
│   (Órdenes)     │    │ Compensación    │    │   + Outbox      │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

### Componentes Clave
- **OrderService**: Fachada de alto nivel con patrones de circuit breaker
- **SagaOrchestrator**: Gestión del flujo de ejecución de sagas
- **CompensationManager**: Maneja transacciones compensatorias para pasos fallidos
- **EventPublisher**: Gestiona publicación de eventos con mecanismos de respaldo

## ✨ Características Principales

### 🛡️ Patrones de Resiliencia
- **Circuit Breakers**: Aislamiento de fallos por paso
- **Bulkheads**: Aislamiento de recursos para ejecuciones concurrentes
- **Mecanismos de Reintento**: Estrategias de back-off configurables
- **Compensación**: Rollback automático de transacciones parciales
- **Event Outbox**: Entrega garantizada de eventos durante caídas de Redis

### 📊 Observabilidad
- **Diagnósticos Mejorados**: Contexto enriquecido usando MDC para logging correlacionado
- **Métricas Detalladas**: Latencia, tasas de éxito/fallo, contadores de reintentos
- **Health Checks**: Endpoints de salud completos para monitoreo

### 🎯 Estándares de Calidad
- **Arquitectura Limpia**: Clara separación de responsabilidades
- **Diseño Basado en Interfaces**: Código testeable y mantenible
- **Pruebas Exhaustivas**: Pruebas unitarias e integración con TestContainers
- **Listo para Producción**: Diseñado para fácil troubleshooting y monitoreo

## 📋 Flujo de Procesamiento de Órdenes

```
Orden Creada → Validación → Pago → Verificar Stock → Envío → Entrega → Completada
     ↓             ↓         ↓          ↓            ↓        ↓         ↓
Compensación  ←──────────────────────────────────────────────────────────┘
  (si es necesaria)
```

### Transiciones de Estado
- **Flujo Exitoso**: `ORDER_CREATED` → `VALIDATED` → `PAYMENT_CONFIRMED` → `DELIVERED` → `COMPLETED`
- **Manejo de Fallos**: Compensación automática con seguimiento detallado de errores
- **Revisión Manual**: Fallos complejos escalados para intervención humana

## 🔧 Configuración

### Perfiles de Aplicación
- `default`: Desarrollo local
- `prod`: Optimizaciones para producción
- `integration`: Pruebas de integración con TestContainers
- `stress`: Configuración para pruebas de estrés

### Variables de Entorno
```bash
# Base de datos
SPRING_R2DBC_URL=r2dbc:mysql://localhost:3306/orders
SPRING_R2DBC_USERNAME=root
SPRING_R2DBC_PASSWORD=root

# Redis
SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379

# Monitoreo
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics,prometheus
```

## 📡 Ejemplos de API

### Crear Orden
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": 12345,
    "items": [{"productId": "PROD-001", "quantity": 2}],
    "totalAmount": 99.99
  }'
```

### Verificar Estado de Orden
```bash
curl http://localhost:8080/api/orders/{orderId}/status
```

### Health Check
```bash
curl http://localhost:8080/actuator/health
```

## 🧪 Pruebas

```bash
# Pruebas unitarias
mvn test

# Pruebas de integración con TestContainers
mvn test -Pintegration

# Análisis SonarQube
mvn clean verify sonar:sonar -Dsonar.token=YOUR_TOKEN

# Pruebas de estrés
mvn test -Pstress
```

## 📊 Monitoreo y Métricas

- **Endpoints de Salud**: `/actuator/health`
- **Métricas**: `/actuator/metrics`
- **Prometheus**: `/actuator/prometheus`

### Métricas Clave
- `saga.execution.duration`: Tiempo de ejecución de saga
- `compensation.triggered.count`: Eventos de compensación
- `circuit.breaker.state`: Estados de circuit breaker
- `event.publishing.success.rate`: Tasa de éxito de entrega de eventos

## 🚨 Solución de Problemas

### Problemas Comunes

**Problemas de Conexión a Base de Datos**
```bash
# Verificar conectividad
docker exec -it mysql-container mysql -u root -p -e "SELECT 1"
```

**Problemas de Conexión a Redis**
```bash
# Verificar Redis
docker exec -it redis-container redis-cli ping
```

**La Aplicación No Inicia**
- Verificar versión de Java: `java --version`
- Verificar disponibilidad de puerto: `netstat -tulpn | grep 8080`
- Revisar logs: `tail -f logs/application.log`

## 🛣️ Hoja de Ruta

- [ ] Implementación del componente Suscriptor (SUB)
- [ ] Optimización de rendimiento para escenarios de alto throughput
- [ ] Soporte para despliegue multi-región
- [ ] Dashboards de monitoreo avanzados
- [ ] Procedimientos almacenados para operaciones de base de datos

## 🤝 Contribuir

¡Damos la bienvenida a contribuciones! Por favor consulta [CONTRIBUTING.md](docs/CONTRIBUTING.md) para las pautas.

### Configuración de Desarrollo
1. Hacer fork del repositorio
2. Crear rama de característica: `git checkout -b feature/caracteristica-increible`
3. Realizar cambios y agregar pruebas
4. Ejecutar verificaciones de calidad: `mvn clean verify`
5. Enviar pull request

## 📚 Documentación

- [Arquitectura Detallada](docs/ARCHITECTURE_ES.md)
- [Guía de Despliegue](docs/DEPLOYMENT_ES.md)
- [Documentación de API](docs/API_ES.md)
- [Optimización de Rendimiento](docs/PERFORMANCE_ES.md)
- [Guía de Migración](docs/MIGRATION_ES.md)

## 👥 Autores

- **Alonso Isidoro Roman** - *Desarrollador Principal y Arquitecto* - [alonsoir@gmail.com](mailto:alonsoir@gmail.com)
- **Claude Sonnet (Anthropic)** - *Co-Desarrollador IA* - Compañero de programación colaborativa

> Este proyecto demuestra el poder de la colaboración humano-IA en el desarrollo de software, combinando la experiencia 
> humana, conocimiento del dominio y pensamiento estratégico con la generación rápida de código de la IA, sugerencias de 
> optimización y capacidades de pruebas exhaustivas.

## 📄 Licencia

Este proyecto está licenciado bajo la Licencia MIT - consulta el archivo [LICENSE](LICENSE) para más detalles.

## 🙏 Agradecimientos

- Equipo de Spring por el excelente framework reactivo
- Equipo de TestContainers por las pruebas de integración simplificadas
- La comunidad open-source por la inspiración y mejores prácticas
- Anthropic por Claude Sonnet, un compañero de programación IA excepcional

---

**⭐ ¡Si este proyecto te ayuda, por favor dale una estrella!**

Para preguntas o comentarios, contacta [alonsoir@gmail.com](mailto:alonsoir@gmail.com)