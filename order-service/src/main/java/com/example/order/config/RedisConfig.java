package com.example.order.config;

import com.example.order.events.EventConsumer;
import com.example.order.events.EventWrapper;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import reactor.core.publisher.Mono;

import java.util.Map;

@Configuration
public class RedisConfig {

    public static final String EVENT_TOPIC = "order.events";

    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    @Bean
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory,
            ObjectMapper redisObjectMapper) {

        Jackson2JsonRedisSerializer<Object> serializer =
                new Jackson2JsonRedisSerializer<>(redisObjectMapper, Object.class);

        RedisSerializationContext<String, Object> context = RedisSerializationContext
                .<String, Object>newSerializationContext(new StringRedisSerializer())
                .value(serializer)
                .hashKey(new StringRedisSerializer())
                .hashValue(serializer)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }

    @Bean
    public ReactiveRedisTemplate<String, Map<String, Object>> mapReactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory,
            ObjectMapper redisObjectMapper) {

        Jackson2JsonRedisSerializer<Map> serializer =
                new Jackson2JsonRedisSerializer<>(redisObjectMapper, Map.class);

        RedisSerializationContext<String, Map<String, Object>> context = RedisSerializationContext
                .<String, Map<String, Object>>newSerializationContext(new StringRedisSerializer())
                .value((RedisSerializationContext.SerializationPair<Map<String, Object>>) serializer)
                .hashKey(new StringRedisSerializer())
                .hashValue(serializer)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }

    @Bean
    @Profile("!test")
    public ReactiveRedisMessageListenerContainer reactiveRedisMessageListenerContainer(
            ReactiveRedisConnectionFactory connectionFactory) {
        try {
            return new ReactiveRedisMessageListenerContainer(connectionFactory);
        } catch (Exception e) {
            // Log de la excepción
            System.err.println("Error al crear el contenedor de mensajes Redis: " + e.getMessage());
            return null; // En caso de error, devolvemos null y Spring no inicializará este bean
        }
    }

    @Bean
    @Profile("!test")
    public CommandLineRunner subscribeToRedisChannel(
            ReactiveRedisMessageListenerContainer container,
            EventConsumer consumer,
            ObjectMapper objectMapper) {

        return args -> {
            if (container != null) {
                container
                        .receive(ChannelTopic.of(EVENT_TOPIC))
                        .map(message -> {
                            try {
                                return objectMapper.readValue(message.getMessage(), EventWrapper.class);
                            } catch (Exception e) {
                                throw new RuntimeException("Error deserializando el mensaje", e);
                            }
                        })
                        .flatMap(event -> Mono.fromRunnable(() -> consumer.handleEvent(event)))
                        .subscribe();
            }
        };
    }
}