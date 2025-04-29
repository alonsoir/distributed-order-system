package com.example.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;

@Testcontainers
class SimpleTest {
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
}