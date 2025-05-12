# ===== ETAPA DE COMPILACIÓN =====
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copiar archivos del proyecto
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY src src

# Definimos un cache para las dependencias de Maven
RUN --mount=type=cache,target=/root/.m2 ./mvnw clean package -DskipTests

# Extraer layers del JAR para optimizar el caching de Docker
RUN mkdir -p target/extracted
RUN java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ===== ETAPA DE EJECUCIÓN =====
FROM eclipse-temurin:21-jre-alpine AS runtime

# Crear usuario no-root para la ejecución
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Directorio de configuración de seguridad
RUN mkdir -p /opt/app/config && \
    mkdir -p /opt/app/logs && \
    mkdir -p /opt/app/dlq && \
    mkdir -p /tmp/dlq-processing

# Instalar herramientas mínimas necesarias y dependencias
RUN apk add --no-cache tini tzdata curl dumb-init && \
    apk upgrade --no-cache

# Configuración de zonas horarias
ENV TZ=UTC

# Establecer directorio de trabajo
WORKDIR /opt/app

# Copiamos las capas de la aplicación desde la etapa de compilación
COPY --from=builder /build/target/extracted/dependencies/ ./
COPY --from=builder /build/target/extracted/spring-boot-loader/ ./
COPY --from=builder /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/target/extracted/application/ ./

# Copiar el archivo de esquema SQL
COPY src/main/docker/mysql-init/orders.sql /docker-entrypoint-initdb.d/orders.sql

# Asignar permisos correctos para directorios
RUN chown -R appuser:appgroup /opt/app && \
    chown -R appuser:appgroup /tmp/dlq-processing && \
    chmod -R 755 /opt/app

# Exponer puertos necesarios (ajustar según la aplicación)
# Puerto HTTP
EXPOSE 8080
# Puerto de métricas para Prometheus
EXPOSE 8081
# Puerto para actuator
EXPOSE 8082
# Puerto para JMX
EXPOSE 9999

# Configuración JVM optimizada para análisis de rendimiento con Flight Recorder
ENV JAVA_OPTS="\
# Opciones base de memoria y contenedores \
-XX:+UseContainerSupport \
-XX:MaxRAMPercentage=75.0 \
-XX:InitialRAMPercentage=60.0 \
-XX:MinRAMPercentage=50.0 \
\
# Optimizaciones de GC para aplicaciones reactivas \
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=100 \
-XX:+ParallelRefProcEnabled \
-XX:G1HeapRegionSize=4m \
-XX:+AlwaysPreTouch \
-XX:+DisableExplicitGC \
\
# Optimizaciones para microservicios \
-XX:+OptimizeStringConcat \
-XX:+UseStringDeduplication \
-XX:+ScavengeBeforeFullGC \
-XX:+ClassUnloadingWithConcurrentMark \
\
# Configuración para Java Flight Recorder \
-XX:+FlightRecorder \
-XX:FlightRecorderOptions=stackdepth=256 \
-XX:+UnlockDiagnosticVMOptions \
-XX:+DebugNonSafepoints \
\
# Configuración para diagnóstico de rendimiento \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/opt/app/logs/heapdump.hprof \
-XX:ErrorFile=/opt/app/logs/jvm_error_%p.log \
\
# Optimizaciones para rendimiento en general \
-XX:+PerfDisableSharedMem \
-XX:CompileThreshold=10000 \
\
# Configuración para JMX (para conectar herramientas de monitoreo) \
-Dcom.sun.management.jmxremote \
-Dcom.sun.management.jmxremote.port=9999 \
-Dcom.sun.management.jmxremote.ssl=false \
-Dcom.sun.management.jmxremote.authenticate=false \
-Dcom.sun.management.jmxremote.local.only=false \
\
# Habilitar async-profiler si está instalado \
-XX:+ExtendedDTraceProbes \
\
# Opciones NIO específicas para aplicaciones reactivas \
-Djdk.nio.maxCachedBufferSize=262144 \
-Dreactor.netty.pool.leasingStrategy=lifo \
"

# Variables para habilitar/deshabilitar JFR según necesidad
ENV JFR_ENABLED="true"
ENV JFR_FILENAME="/opt/app/logs/recording.jfr"
ENV JFR_DURATION="0s"
ENV JFR_OPTIONS="dumponexit=true"

# Variables de entorno específicas de la aplicación
ENV SPRING_PROFILES_ACTIVE="production"
ENV REDIS_RECONNECT_ATTEMPTS="10"
ENV REDIS_RECONNECT_DELAY="2000"
ENV DLQ_DIRECTORY="/tmp/dlq-processing"
ENV LOG_LEVEL="INFO"
ENV RESILIENCE_ENABLED="true"

# Metadata para la imagen
LABEL org.opencontainers.image.title="Order Service"
LABEL org.opencontainers.image.description="Microservicio reactivo para la gestión de órdenes con soporte para AT LEAST ONCE y AT MOST ONCE"
LABEL org.opencontainers.image.vendor="Example Company"
LABEL org.opencontainers.image.version="1.0.0-SNAPSHOT"
LABEL org.opencontainers.image.created="2025-05-12"

# Cambiar al usuario no-root
USER appuser

# Usar dumb-init como process manager para manejar señales correctamente
ENTRYPOINT ["/usr/bin/dumb-init", "--"]

# Comando para iniciar la aplicación con configuración JFR condicional
CMD if [ "$JFR_ENABLED" = "true" ]; then \
        RECORDING_OPTS="-XX:StartFlightRecording=disk=true,filename=${JFR_FILENAME},duration=${JFR_DURATION},${JFR_OPTIONS}" ; \
    else \
        RECORDING_OPTS="" ; \
    fi ; \
    java \
    -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} \
    -Dlogging.level.root=${LOG_LEVEL} \
    ${JAVA_OPTS} \
    ${RECORDING_OPTS} \
    org.springframework.boot.loader.JarLauncher

# Healthcheck para verificar que la aplicación está en ejecución
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8082/actuator/health || exit 1