package com.example.order.actuator;

import com.example.order.service.DynamicOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // Usamos LENIENT para evitar UnnecessaryStubbingException
class StrategyConfigurationListenerTest {

    @Mock
    private DynamicOrderService orderService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private Environment environment;

    @Mock
    private ContextRefreshedEvent contextRefreshedEvent;

    private StrategyConfigurationListener listener;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Creamos el listener sin configurar mocks innecesarios en el setup
        listener = new StrategyConfigurationListener(orderService, eventPublisher, environment);
    }

    @Test
    void shouldUpdateStrategyFromEnvironmentProperty() {
        // Arrange
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("atMostOnce");
        when(orderService.setDefaultStrategy("atMostOnce")).thenReturn(true);

        // Act
        listener.updateStrategyFromEnvironment();

        // Assert
        verify(orderService, times(1)).setDefaultStrategy("atMostOnce");
    }

    @Test
    void shouldNotUpdateWhenStrategyIsTheSame() {
        // Arrange
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("atLeastOnce");
        when(orderService.setDefaultStrategy("atLeastOnce")).thenReturn(false); // No cambió

        // Act
        listener.updateStrategyFromEnvironment();

        // Assert
        verify(orderService, times(1)).setDefaultStrategy("atLeastOnce");
    }

    @Test
    void shouldHandleInvalidStrategyFromEnvironment() {
        // Arrange
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("invalidStrategy");
        when(orderService.setDefaultStrategy("invalidStrategy"))
                .thenThrow(new IllegalArgumentException("Strategy not found: invalidStrategy"));

        // Act & Assert
        assertThatCode(() -> listener.updateStrategyFromEnvironment())
                .doesNotThrowAnyException(); // No debería propagar la excepción

        verify(orderService, times(1)).setDefaultStrategy("invalidStrategy");
    }

    @Test
    void shouldUpdateStrategyFromFile() throws IOException {
        // Arrange
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn(null); // No hay property

        // Crear un archivo de configuración temporal
        Path configFile = tempDir.resolve("saga-strategy.conf");
        Files.writeString(configFile, "atMostOnce");

        // Mockear la ruta del archivo de manera correcta
        try (var mockedStatic = mockStatic(Paths.class)) {
            mockedStatic.when(() -> Paths.get("/config/saga-strategy.conf")).thenReturn(configFile);

            when(orderService.setDefaultStrategy("atMostOnce")).thenReturn(true);

            // Act
            listener.updateStrategyFromEnvironment();

            // Assert
            verify(orderService, times(1)).setDefaultStrategy("atMostOnce");
        }
    }

    @Test
    void shouldHandleFileReadError() throws IOException {
        // Arrange
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn(null); // No hay property

        // Crear un archivo real para evitar NullPointerException
        Path configFile = tempDir.resolve("saga-strategy.conf");

        // Mockear Files.exists y Files.readString de manera adecuada
        try (var mockedStatic = mockStatic(Files.class)) {
            mockedStatic.when(() -> Files.exists(any(Path.class))).thenReturn(true);
            mockedStatic.when(() -> Files.readString(any(Path.class))).thenThrow(new IOException("Error reading file"));

            try (var mockedPaths = mockStatic(Paths.class)) {
                mockedPaths.when(() -> Paths.get("/config/saga-strategy.conf")).thenReturn(configFile);

                // Act & Assert
                assertThatCode(() -> listener.updateStrategyFromEnvironment())
                        .doesNotThrowAnyException(); // No debería propagar la excepción

                // Nunca se llamó a setDefaultStrategy porque hubo un error al leer
                verify(orderService, never()).setDefaultStrategy(anyString());
            }
        }
    }

    @Test
    void shouldUpdateStrategyOnContextRefreshedEvent() {
        // Arrange
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("atMostOnce");
        when(orderService.setDefaultStrategy("atMostOnce")).thenReturn(true);

        // Act
        listener.handleRefreshEvent(contextRefreshedEvent);

        // Assert
        verify(orderService, times(1)).setDefaultStrategy("atMostOnce");
    }

    @Test
    void shouldIgnoreNonContextRefreshedEvents() {
        // Arrange
        ApplicationEvent otherEvent = mock(ApplicationEvent.class);

        // Act
        listener.handleRefreshEvent(otherEvent);

        // Assert - nunca se debe llamar a updateStrategyFromEnvironment
        verify(environment, never()).getProperty(anyString());
        verify(orderService, never()).setDefaultStrategy(anyString());
    }

    @Test
    void shouldPrioritizePropertyOverFile() throws IOException {
        // Arrange
        when(environment.getProperty("order.service.strategy.default-strategy")).thenReturn("atMostOnce");

        // Crear un archivo de configuración temporal con una estrategia diferente
        Path configFile = tempDir.resolve("saga-strategy.conf");
        Files.writeString(configFile, "atLeastOnce");

        try (var mockedStatic = mockStatic(Paths.class)) {
            mockedStatic.when(() -> Paths.get("/config/saga-strategy.conf")).thenReturn(configFile);

            when(orderService.setDefaultStrategy("atMostOnce")).thenReturn(true);

            // Act
            listener.updateStrategyFromEnvironment();

            // Assert - debe usar la propiedad del environment, no el archivo
            verify(orderService, times(1)).setDefaultStrategy("atMostOnce");
            verify(orderService, never()).setDefaultStrategy("atLeastOnce");
        }
    }
}