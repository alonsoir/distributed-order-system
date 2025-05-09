package com.example.order.service.util;

import io.lettuce.core.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Logger personalizado que permite indicar que las excepciones son esperadas
 * para reducir el ruido en los logs durante las pruebas.
 */
public class TestAwareLogger {
    private final Logger logger;
    private final boolean testMode;

    private static final Marker EXPECTED_EXCEPTION_MARKER = MarkerFactory.getMarker("TEST_EXPECTED_EXCEPTION");

    public TestAwareLogger(Class<?> clazz) {
        this.logger = LoggerFactory.getLogger(clazz);
        this.testMode = isTestMode();
    }

    private boolean isTestMode() {
        // Detectar si estamos en modo de prueba
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().contains("junit") ||
                    element.getClassName().contains("Test")) {
                return true;
            }
        }
        return false;
    }

    public void error(String message, Throwable t) {
        if (testMode && isExpectedException(t)) {
            // Si estamos en modo de prueba y es una excepción esperada, usar un marcador para filtrar
            logger.error(EXPECTED_EXCEPTION_MARKER, "[TEST-EXPECTED] " + message, t);
        } else {
            logger.error(message, t);
        }
    }

    public void error(String message) {
        logger.error(message);
    }

    public void info(String message) {
        logger.info(message);
    }

    public void debug(String message) {
        logger.debug(message);
    }

    /**
     * Determina si una excepción es probablemente una "excepción esperada"
     * durante las pruebas.
     */
    private boolean isExpectedException(Throwable t) {
        return t instanceof RedisException ||
                t.getMessage().contains("Database unavailable") ||
                t.getMessage().contains("Unexpected error") ||
                t.getMessage().contains("DLQ failure") ||
                t.getMessage().contains("must not be null or empty") ||
                t.getMessage().contains("Null JSON");
    }
}