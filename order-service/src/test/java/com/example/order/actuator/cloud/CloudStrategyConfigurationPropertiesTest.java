package com.example.order.actuator.cloud;

import com.example.order.service.DynamicOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CloudStrategyConfigurationPropertiesTest {

    @Mock
    private DynamicOrderService orderService;

    private CloudStrategyConfigurationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new CloudStrategyConfigurationProperties(orderService);
        // Configuración por defecto para pruebas
        when(orderService.setDefaultStrategy(anyString())).thenReturn(false);
    }

    @Test
    void shouldHaveDefaultStrategy() {
        // Verificar que se inicia con la estrategia por defecto
        assertThat(properties.getDefaultStrategy()).isEqualTo("atLeastOnce");
    }

    @Test
    void shouldUpdateStrategyWhenPropertyChanges() {
        // Arrange
        when(orderService.setDefaultStrategy("atMostOnce")).thenReturn(true);

        // Act
        properties.setDefaultStrategy("atMostOnce");

        // Assert
        assertThat(properties.getDefaultStrategy()).isEqualTo("atMostOnce");
        verify(orderService).setDefaultStrategy("atMostOnce");
    }

    @Test
    void shouldNotNotifyWhenStrategyDoesNotChange() {
        // Arrange
        when(orderService.setDefaultStrategy("atLeastOnce")).thenReturn(false);

        // Act
        properties.setDefaultStrategy("atLeastOnce");

        // Assert
        verify(orderService).setDefaultStrategy("atLeastOnce");
    }

    @Test
    void shouldHandleInvalidStrategyGracefully() {
        // Arrange
        doThrow(new IllegalArgumentException("Invalid strategy")).when(orderService).setDefaultStrategy("invalidStrategy");

        // Act & Assert
        assertThatCode(() -> properties.setDefaultStrategy("invalidStrategy"))
                .doesNotThrowAnyException();

        assertThat(properties.getDefaultStrategy()).isEqualTo("invalidStrategy");
        verify(orderService).setDefaultStrategy("invalidStrategy");
    }

    @Test
    void shouldDetectRefreshScopeEvent() {
        // Arrange
        RefreshScopeRefreshedEvent refreshEvent = new RefreshScopeRefreshedEvent("Test refresh");

        // Act
        properties.handleRefreshEvent(refreshEvent);

        // Assert - no hay acciones explícitas en el método, solo loggin
        // La actualización real se hace a través del setter cuando Spring actualiza la propiedad
        verifyNoInteractions(orderService);
    }

    @Test
    void shouldDetectEnvironmentChangeEvent() {
        // Arrange
        Set<String> keys = new HashSet<>();
        keys.add("order.service.strategy.default-strategy");
        EnvironmentChangeEvent changeEvent = new EnvironmentChangeEvent(keys);

        // Act
        properties.handleRefreshEvent(changeEvent);

        // Assert - no hay acciones explícitas en el método, solo loggin
        // La actualización real se hace a través del setter cuando Spring actualiza la propiedad
        verifyNoInteractions(orderService);
    }

    @Test
    void shouldIgnoreOtherEvents() {
        // Arrange
        RefreshScopeRefreshedEvent otherEvent = mock(RefreshScopeRefreshedEvent.class);
        when(otherEvent.getSource()).thenReturn("test");

        // Act
        properties.handleRefreshEvent(otherEvent);

        // Assert
        verifyNoInteractions(orderService);
    }
}