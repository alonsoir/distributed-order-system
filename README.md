# Order Processing Service

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-green.svg)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-7+-red.svg)](https://redis.io/)
[![MySQL](https://img.shields.io/badge/MySQL-8+-blue.svg)](https://www.mysql.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A robust, reactive microservice for order processing using the Saga pattern, built with Spring WebFlux, R2DBC, and Redis. 
Designed for fault-tolerant, scalable order management in distributed environments.

> **Note**: This project was developed through collaborative human-AI programming using Claude Sonnet, representing an 
> innovative approach to software development that combines human experience and judgment with AI's rapid code generation 
> and optimization capabilities.

## 🚀 Quick Start

### Prerequisites
- Java 17+ (tested with Java 21 and 23)
- Maven 3.8+
- Docker Desktop
- MySQL 8+ and Redis 7+ (or use TestContainers)

### Installation & Running

```bash
# Clone repository
git clone https://github.com/yourusername/distributed-order-system.git
cd distributed-order-system

# Build project
mvn clean install

# Run tests
mvn test

# Start application
mvn spring-boot:run
```

### Docker Setup
```bash
# Start dependencies
docker-compose up -d mysql redis

# Run application
./scripts/run-local.sh
```

## 🏗️ Architecture Overview

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Order API     │───▶│ Saga Orchestr.  │───▶│  Event Publisher│
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   MySQL R2DBC   │    │ Compensation    │    │  Redis Streams  │
│   (Orders)      │    │   Manager       │    │   + Outbox      │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

### Key Components
- **OrderService**: High-level facade with circuit breaker patterns
- **SagaOrchestrator**: Core saga execution flow management
- **CompensationManager**: Handles compensating transactions for failed steps
- **EventPublisher**: Manages event publication with fallback mechanisms

## ✨ Key Features

### 🛡️ Resilience Patterns
- **Circuit Breakers**: Per-step failure isolation
- **Bulkheads**: Resource isolation for concurrent executions
- **Retry Mechanisms**: Configurable back-off strategies
- **Compensation**: Automatic rollback of partial transactions
- **Event Outbox**: Guaranteed event delivery during Redis outages

### 📊 Observability
- **Enhanced Diagnostics**: Rich context using MDC for correlated logging
- **Detailed Metrics**: Latency, success/failure rates, retry counters
- **Health Checks**: Comprehensive health endpoints for monitoring

### 🎯 Quality Standards
- **Clean Architecture**: Clear separation of concerns
- **Interface-Based Design**: Testable and maintainable code
- **Comprehensive Testing**: Unit and integration tests with TestContainers
- **Production-Ready**: Designed for easy troubleshooting and monitoring

## 📋 Order Processing Flow

```
Order Created → Validation → Payment → Stock Check → Shipping → Delivery → Completed
     ↓             ↓           ↓          ↓           ↓          ↓         ↓
Compensation  ←────────────────────────────────────────────────────────────┘
  (if needed)
```

### State Transitions
- **Happy Path**: `ORDER_CREATED` → `VALIDATED` → `PAYMENT_CONFIRMED` → `DELIVERED` → `COMPLETED`
- **Failure Handling**: Automatic compensation with detailed error tracking
- **Manual Review**: Complex failures escalated for human intervention

## 🔧 Configuration

### Application Profiles
- `default`: Local development
- `prod`: Production optimizations
- `integration`: Integration testing with TestContainers
- `stress`: Stress testing configuration

### Environment Variables
```bash
# Database
SPRING_R2DBC_URL=r2dbc:mysql://localhost:3306/orders
SPRING_R2DBC_USERNAME=root
SPRING_R2DBC_PASSWORD=root

# Redis
SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379

# Monitoring
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics,prometheus
```

## 📡 API Examples

### Create Order
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": 12345,
    "items": [{"productId": "PROD-001", "quantity": 2}],
    "totalAmount": 99.99
  }'
```

### Check Order Status
```bash
curl http://localhost:8080/api/orders/{orderId}/status
```

### Health Check
```bash
curl http://localhost:8080/actuator/health
```

## 🧪 Testing

```bash
# Unit tests
mvn test

# Integration tests with TestContainers
mvn test -Pintegration

# SonarQube analysis
mvn clean verify sonar:sonar -Dsonar.token=YOUR_TOKEN

# Stress testing
mvn test -Pstress
```

## 📊 Monitoring & Metrics

- **Health Endpoints**: `/actuator/health`
- **Metrics**: `/actuator/metrics`
- **Prometheus**: `/actuator/prometheus`

### Key Metrics
- `saga.execution.duration`: Saga execution time
- `compensation.triggered.count`: Compensation events
- `circuit.breaker.state`: Circuit breaker states
- `event.publishing.success.rate`: Event delivery success rate

## 🚨 Troubleshooting

### Common Issues

**Database Connection Issues**
```bash
# Check connectivity
docker exec -it mysql-container mysql -u root -p -e "SELECT 1"
```

**Redis Connection Issues**
```bash
# Check Redis
docker exec -it redis-container redis-cli ping
```

**Application Won't Start**
- Verify Java version: `java --version`
- Check port availability: `netstat -tulpn | grep 8080`
- Review logs: `tail -f logs/application.log`

## 🛣️ Roadmap

- [ ] Subscriber (SUB) component implementation
- [ ] Performance optimization for high-throughput scenarios
- [ ] Multi-region deployment support
- [ ] Advanced monitoring dashboards
- [ ] Stored procedures for database operations

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](docs/CONTRIBUTING.md) for guidelines.

### Development Setup
1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Make changes and add tests
4. Run quality checks: `mvn clean verify`
5. Submit a pull request

## 📚 Documentation

- [Architecture Deep Dive](docs/ARCHITECTURE.md)
- [Deployment Guide](docs/DEPLOYMENT.md)
- [API Documentation](docs/API.md)
- [Performance Tuning](docs/PERFORMANCE.md)
- [Migration Guide](docs/MIGRATION.md)

## 👥 Authors

- **Alonso Isidoro Roman** - *Lead Developer & Architect* - [alonsoir@gmail.com](mailto:alonsoir@gmail.com)
- **Claude Sonnet (Anthropic)** - *AI Co-Developer* - Collaborative programming partner

> This project demonstrates the power of human-AI collaboration in software development, combining human experience, 
> domain knowledge, and strategic thinking with AI's rapid code generation, optimization suggestions, and comprehensive 
> testing capabilities.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Spring Team for the excellent reactive framework
- TestContainers team for simplified integration testing
- The open-source community for inspiration and best practices
- Anthropic for Claude Sonnet, an exceptional AI programming partner

---

**⭐ If this project helps you, please give it a star!**

For questions or feedback, please contact [alonsoir@gmail.com](mailto:alonsoir@gmail.com)