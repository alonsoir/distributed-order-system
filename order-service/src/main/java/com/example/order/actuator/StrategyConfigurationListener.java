package com.example.order.actuator;

import com.example.order.service.DynamicOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class StrategyConfigurationListener {
    private static final Logger log = LoggerFactory.getLogger(StrategyConfigurationListener.class);

    private final DynamicOrderService orderService;
    private final Environment environment;

    public StrategyConfigurationListener(
            DynamicOrderService orderService,
            ApplicationEventPublisher eventPublisher,
            Environment environment) {
        this.orderService = orderService;
        this.environment = environment;

        // No necesitamos publicar un evento personalizado en el constructor
        log.info("Strategy configuration listener initialized");
    }

    @EventListener
    public void handleRefreshEvent(ApplicationEvent event) {
        // Usamos eventos estándar de Spring
        if (event instanceof ContextRefreshedEvent) {
            log.info("Context refresh event detected, checking for saga strategy changes");
            updateStrategyFromEnvironment();
        }
    }

    // Método que se llama periódicamente para verificar cambios de configuración
    @Scheduled(fixedDelayString = "${order.service.strategy.check-interval:30000}")
    public void updateStrategyFromEnvironment() {
        // Primero intentamos leer desde properties
        String propStrategy = environment.getProperty("order.service.strategy.default-strategy");
        if (propStrategy != null && !propStrategy.isEmpty()) {
            tryUpdateStrategy(propStrategy);
            return;
        }

        // Como alternativa, leemos de un archivo de configuración
        Path configFile = Paths.get("/config/saga-strategy.conf");
        if (Files.exists(configFile)) {
            try {
                String fileStrategy = Files.readString(configFile).trim();
                if (!fileStrategy.isEmpty()) {
                    tryUpdateStrategy(fileStrategy);
                }
            } catch (IOException e) {
                log.error("Error reading strategy configuration file: {}", e.getMessage());
            }
        }
    }

    private void tryUpdateStrategy(String newStrategy) {
        try {
            boolean changed = orderService.setDefaultStrategy(newStrategy);
            if (changed) {
                log.info("Saga strategy updated from external configuration to: {}", newStrategy);
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid strategy in configuration: {}", newStrategy);
        }
    }
}