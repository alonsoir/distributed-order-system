package com.example.order.service.unit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("unit")
class SimpleTest {

    private static RedisServer redisServer;

    @BeforeAll
    public static void startRedis() throws IOException {
        redisServer = new RedisServer(6379);
        redisServer.start();
    }

    @AfterAll
    public static void stopRedis() {
        redisServer.stop();
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void printBeans() {
        String[] beanNames = context.getBeanDefinitionNames();
        Arrays.sort(beanNames);
        for (String beanName : beanNames) {
            System.out.println(beanName);
        }
    }

    @Test
    void shouldCompile() {
        // Test vacío para verificar compilación
    }

    @Test
    void contextLoads() {
        assertNotNull(context);
    }
}