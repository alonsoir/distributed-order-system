package com.example.order.repository.config;

import com.example.order.repository.base.SecurityUtils;
import com.example.order.repository.base.ValidationUtils;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;

@Configuration
public class DatabaseConfig {

    @Bean
    public ReactiveTransactionManager reactiveTransactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    @Bean
    public TransactionalOperator r2dbcTransactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }

    @Bean
    public SecurityUtils securityUtils() {
        return new SecurityUtils();
    }

    @Bean
    public ValidationUtils validationUtils() {
        return new ValidationUtils();
    }
}