package com.example.order.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationProperties(prefix = "event.persistence")
@Getter
@Setter
public class EventPersistenceConfig {
    private Map<String, PersistenceConfig> topics;

    @Getter
    @Setter
    public static class PersistenceConfig {
        private boolean persistToDisk;
        private Importance importance;
    }

    public enum Importance {
        LOW, MEDIUM, HIGH
    }

    public boolean shouldPersistToDisk(String topic) {
        PersistenceConfig config = topics.getOrDefault(topic, new PersistenceConfig());
        return config.persistToDisk || config.importance == Importance.HIGH;
    }
}