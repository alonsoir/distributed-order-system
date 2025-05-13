package com.example.order.actuator;

import com.example.order.service.DynamicOrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@ActiveProfiles("unit")

class StrategyConfigurationManagerTest {

    @Mock
    private DynamicOrderService orderService;

    @Mock
    private Environment environment;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private Counter counter;

    @Mock
    private Timer timer;

    @Mock
    private Timer.Sample timerSample;

    private MeterRegistry meterRegistry;
    private StrategyConfigurationManager manager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        // Configurar mocks
        Set<String> availableStrategies = new HashSet<>(Arrays.asList("atLeastOnce", "atMostOnce"));
        when(orderService.getAvailableStrategies()).thenReturn(availableStrategies);
        when(orderService.getDefaultStrategy()).thenReturn("atLeastOnce");
        when(orderService.setDefaultStrategy(anyString())).thenReturn(false); // Por defecto, no hay cambios

        // Crear un MeterRegistry real para simplificar las pruebas
        meterRegistry = new SimpleMeterRegistry();

        // Crear y configurar el manager
        manager = new StrategyConfigurationManager(
                orderService,
                environment,
                meterRegistry,
                eventPublisher,
                applicationContext
        );

        // Establecer propiedades a través de reflection
        ReflectionTestUtils.setField(manager, "configFilePath", tempDir.resolve("saga-strategy.conf").toString());
        ReflectionTestUtils.setField(manager, "enableCloudEvents", true);
        ReflectionTestUtils.setField(manager, "reconciliationIntervalMs", 60000L);

        // Inicializar
        manager.afterPropertiesSet();
    }

    @Test
    void shouldInitializeWithCorrectDefaults() {
        // Verificar que el manager se inicializó correctamente
        assertThat(manager.getStrategyInfo()).containsKey("currentStrategy");
        assertThat(manager.getStrategyInfo()).containsKey("availableStrategies");

        // Verificar estrategia inicial
        assertThat(manager.getStrategyInfo().get("currentStrategy")).isEqualTo("atLeastOnce");
    }

    @Test
    void shouldApplyManualOverrideWithHighestPriority() {
        // Configurar mocks
        when(orderService.setDefaultStrategy("atMostOnce")).thenReturn(true);

        // Establecer override manual
        Map<String, Object> result = manager.updateStrategy("atMostOnce", true);

        // Verificar resultado
        assertThat(result.get("status")).isEqualTo("success");
        assertThat(result.get("message")).isEqualTo("Manual override set: atMostOnce");

        // Verificar que se aplicó la estrategia
        verify(orderService).setDefaultStrategy("atMostOnce");

        // Verificar fuente activa
        assertThat(manager.getStrategyInfo().get("activeSource")).isEqualTo("manual");
        assertThat(manager.getStrategyInfo().get("manualOverride")).isEqualTo(true);
    }

    @Test
    void shouldClearManualOverride() {
        // Primero establecer un override
        when(orderService.setDefaultStrategy("atMostOnce")).thenReturn(true);
        manager.updateStrategy("atMostOnce", true);

        // Luego limpiarlo
        Map<String, Object> result = manager.updateStrategy(null, false);

        // Verificar resultado
        assertThat(result.get("status")).isEqualTo("success");
        assertThat(result.get("message")).isEqualTo("Manual override cleared");

        // Verificar que se intentó actualizar desde otras fuentes
        verify(environment, atLeastOnce()).getProperty("order.service.strategy.default-strategy");
    }

    @Test
    void shouldUpdateFromEnvironment() {
        // Configurar mock para environment
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("atMostOnce");
        when(orderService.setDefaultStrategy("atMostOnce")).thenReturn(true);

        // Forzar actualización
        manager.updateConfigurationFromAllSources();

        // Verificar que se aplicó la estrategia
        verify(orderService).setDefaultStrategy("atMostOnce");

        // Verificar fuente activa
        assertThat(manager.getStrategyInfo().get("activeSource")).isEqualTo("environment");
    }

    @Test
    void shouldUpdateFromConfigFile() throws IOException {
        // Configurar mock para environment (sin valor)
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn(null);

        // Configurar archivo de configuración
        Path configFile = tempDir.resolve("saga-strategy.conf");
        Files.writeString(configFile, "atMostOnce");

        // Configurar mock para service
        when(orderService.setDefaultStrategy("atMostOnce")).thenReturn(true);

        // Forzar actualización
        manager.updateConfigurationFromAllSources();

        // Verificar que se aplicó la estrategia
        verify(orderService).setDefaultStrategy("atMostOnce");

        // Verificar fuente activa
        assertThat(manager.getStrategyInfo().get("activeSource")).isEqualTo("file");
    }

    @Test
    void shouldHandleInvalidStrategy() {
        // Configurar mock para environment
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("invalidStrategy");

        // Configurar mock para service
        when(orderService.setDefaultStrategy("invalidStrategy"))
                .thenThrow(new IllegalArgumentException("Invalid strategy"));

        // Act & Assert - No debería lanzar excepción
        manager.updateConfigurationFromAllSources();

        // Verificar intentos
        verify(orderService).setDefaultStrategy("invalidStrategy");
    }

    @Test
    void shouldRespectPriorities() throws IOException {
        // Configurar fuentes con diferentes valores
        // 1. Environment (prioridad media)
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("fromEnvironment");

        // 2. Archivo (prioridad baja)
        Path configFile = tempDir.resolve("saga-strategy.conf");
        Files.writeString(configFile, "fromFile");

        // Configurar mocks de service
        when(orderService.setDefaultStrategy("fromEnvironment")).thenReturn(true);
        when(orderService.setDefaultStrategy("fromFile")).thenReturn(true);

        // Forzar actualización - debería elegir Environment sobre File
        manager.updateConfigurationFromAllSources();
        verify(orderService).setDefaultStrategy("fromEnvironment");
        verify(orderService, never()).setDefaultStrategy("fromFile");

        // Ahora establecer override manual (prioridad alta)
        when(orderService.setDefaultStrategy("fromManual")).thenReturn(true);
        manager.updateStrategy("fromManual", true);

        // Forzar otra actualización - debería mantener la manual
        reset(orderService);
        when(orderService.getDefaultStrategy()).thenReturn("fromManual");
        manager.updateConfigurationFromAllSources();
        verify(orderService, never()).setDefaultStrategy("fromEnvironment");
        verify(orderService, never()).setDefaultStrategy("fromFile");
    }

    @Test
    void shouldHandleContextRefreshedEvent() {
        // Configurar mocks
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("fromContextEvent");
        when(orderService.getDefaultStrategy()).thenReturn("atLeastOnce"); // Estado inicial diferente
        when(orderService.setDefaultStrategy("fromContextEvent")).thenReturn(true);

        // Enviar evento ContextRefreshedEvent
        ContextRefreshedEvent refreshEvent = new ContextRefreshedEvent(applicationContext);
        manager.handleConfigurationEvent(refreshEvent);

        // Verificar
        verify(orderService).setDefaultStrategy("fromContextEvent");
    }

    @Test
    void shouldHandleRefreshScopeRefreshedEvent() {
        // Configurar mocks con valor diferente para asegurar que se llama a setDefaultStrategy
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("fromCloudEvent");
        when(orderService.getDefaultStrategy()).thenReturn("differentStrategy"); // Estado inicial diferente
        when(orderService.setDefaultStrategy("fromCloudEvent")).thenReturn(true);

        // Resetear estado interno usando el método público en lugar de reflection
        manager.forceReconciliation();

        // Ahora mockear para evitar que forceReconciliation cambie la estrategia inmediatamente
        reset(orderService);
        when(orderService.getDefaultStrategy()).thenReturn("differentStrategy");
        when(orderService.setDefaultStrategy("fromCloudEvent")).thenReturn(true);

        // Enviar evento RefreshScopeRefreshedEvent
        RefreshScopeRefreshedEvent cloudEvent = new RefreshScopeRefreshedEvent("test");
        manager.handleConfigurationEvent(cloudEvent);

        // Verificar
        verify(orderService).setDefaultStrategy("fromCloudEvent");
    }

    @Test
    void shouldHandleEnvironmentChangeEvent() {
        // Configurar mocks con valor diferente
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("fromEnvChangeEvent");
        when(orderService.getDefaultStrategy()).thenReturn("yetAnotherStrategy"); // Estado inicial diferente
        when(orderService.setDefaultStrategy("fromEnvChangeEvent")).thenReturn(true);

        // Resetear estado interno usando el método público en lugar de reflection
        manager.forceReconciliation();

        // Ahora mockear para evitar que forceReconciliation cambie la estrategia inmediatamente
        reset(orderService);
        when(orderService.getDefaultStrategy()).thenReturn("yetAnotherStrategy");
        when(orderService.setDefaultStrategy("fromEnvChangeEvent")).thenReturn(true);

        // Crear evento EnvironmentChangeEvent con la propiedad relevante
        Set<String> keys = new HashSet<>();
        keys.add("order.service.strategy.default-strategy");
        EnvironmentChangeEvent changeEvent = new EnvironmentChangeEvent(keys);

        // Enviar evento
        manager.handleConfigurationEvent(changeEvent);

        // Verificar
        verify(orderService).setDefaultStrategy("fromEnvChangeEvent");
    }

    @Test
    void shouldProvideAuditLog() {
        // Configurar mocks
        when(orderService.setDefaultStrategy("strategy1")).thenReturn(true);
        when(orderService.setDefaultStrategy("strategy2")).thenReturn(true);

        // Realizar varios cambios para generar entradas en el log
        manager.updateStrategy("strategy1", true);
        manager.updateStrategy("strategy2", true);

        // Verificar que hay entradas en el log
        @SuppressWarnings("unchecked")
        Map<String, String> auditLog = (Map<String, String>) manager.getStrategyInfo().get("lastChanges");

        assertThat(auditLog).isNotEmpty();
        assertThat(auditLog.values())
                .anyMatch(entry -> entry.contains("strategy1"))
                .anyMatch(entry -> entry.contains("strategy2"));
    }

    @Test
    void shouldProvideStrategyStatistics() {
        // Verificar estadísticas básicas
        Map<String, Object> stats = manager.getStrategyStatistics();

        assertThat(stats).containsKey("currentStrategy");
        assertThat(stats).containsKey("activeSource");
        assertThat(stats).containsKey("availableStrategies");
        assertThat(stats).containsKey("changeCount");
    }

    @Test
    void shouldHandleReconciliation() {
        // Configurar mocks
        when(orderService.setDefaultStrategy("reconciled")).thenReturn(true);
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("reconciled");

        // Forzar reconciliación
        manager.forceReconciliation();

        // Verificar que se intentó actualizar
        verify(orderService).setDefaultStrategy("reconciled");
    }
}