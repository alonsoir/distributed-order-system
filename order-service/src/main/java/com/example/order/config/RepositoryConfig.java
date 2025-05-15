package com.example.order.config;

import com.example.order.repository.EventRepository;
import com.example.order.repository.R2dbcEventRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class RepositoryConfig {

    @Bean
    public EventRepository eventRepository(DatabaseClient databaseClient, TransactionalOperator transactionalOperator) {
        return new R2dbcEventRepository(databaseClient, transactionalOperator);
    }
}
