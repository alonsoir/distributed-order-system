package com.example.order.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class IdGeneratorMetricsAspect {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Object> generatedIds = new ConcurrentHashMap<>();

    @Around("execution(* com.example.order.service.IdGenerator.*(..))")
    public Object measureGenerationTime(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getMethod().getName();

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Ejecuta el método original
            Object result = joinPoint.proceed();

            // Registra el tiempo
            sample.stop(meterRegistry.timer("id.generation.time",
                    "method", methodName,
                    "success", "true"));

            // Verifica colisiones en modo debug/test
            if (shouldCheckCollisions()) {
                checkForCollision(methodName, result);
            }

            return result;
        } catch (Throwable e) {
            // Registra tiempo en caso de error
            sample.stop(meterRegistry.timer("id.generation.time",
                    "method", methodName,
                    "success", "false"));

            // Incrementa contador de errores
            meterRegistry.counter("id.generation.errors",
                    "method", methodName,
                    "error", e.getClass().getSimpleName()).increment();

            log.error("Error generating ID with method {}: {}", methodName, e.getMessage(), e);
            throw e;
        }
    }

    private boolean shouldCheckCollisions() {
        // Configurable por propiedades
        return Boolean.getBoolean("app.id.check-collisions");
    }

    private void checkForCollision(String methodName, Object newId) {
        String key = methodName + ":" + newId.toString();

        if (generatedIds.putIfAbsent(key, Boolean.TRUE) != null) {
            // ¡Colisión detectada!
            log.error("ID COLLISION DETECTED for method {} with value {}", methodName, newId);
            meterRegistry.counter("id.generation.collisions", "method", methodName).increment();
        }

        // Limpieza periódica para evitar memory leak
        if (generatedIds.size() > 10000) {
            // Implementar limpieza inteligente según necesidades
        }
    }
}