package com.example.order.actuator.cloud;

import com.example.order.service.DynamicOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "order.service.strategy")
@ConditionalOnClass(name = "org.springframework.cloud.context.scope.refresh.RefreshScope")
public class CloudStrategyConfigurationProperties {

    private static final Logger log = LoggerFactory.getLogger(CloudStrategyConfigurationProperties.class);

    private final DynamicOrderService orderService;
    private String defaultStrategy = "atLeastOnce";

    public CloudStrategyConfigurationProperties(DynamicOrderService orderService) {
        this.orderService = orderService;
        log.info("Cloud-based strategy configuration initialized");
    }

    // Getters y setters
    public String getDefaultStrategy() {
        return defaultStrategy;
    }

    public void setDefaultStrategy(String defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
        // Intentar actualizar la estrategia cuando cambia la propiedad
        try {
            boolean changed = orderService.setDefaultStrategy(defaultStrategy);
            if (changed) {
                log.info("Saga strategy updated to: {}", defaultStrategy);
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid strategy in configuration: {}", defaultStrategy);
        }
    }

    @EventListener
    public void handleRefreshEvent(ApplicationEvent event) {
        // Reaccionar específicamente a eventos de Spring Cloud Config
        if (event instanceof RefreshScopeRefreshedEvent ||
                event instanceof EnvironmentChangeEvent) {
            log.info("Cloud configuration refresh detected");
            // La actualización ya ocurre en el setter cuando Spring actualiza la propiedad
        }
    }
}