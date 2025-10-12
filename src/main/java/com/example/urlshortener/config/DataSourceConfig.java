package com.example.urlshortener.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.shard0")
    public DataSourceProperties shard0Properties() {
        return new DataSourceProperties();
    }

    @Bean(name = "shard0")
    public DataSource shard0(@Qualifier("shard0Properties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.shard1")
    public DataSourceProperties shard1Properties() {
        return new DataSourceProperties();
    }

    @Bean(name = "shard1")
    public DataSource shard1(@Qualifier("shard1Properties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("shard0") DataSource shard0, @Qualifier("shard1") DataSource shard1) {
        ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("shard0", shard0);
        targetDataSources.put("shard1", shard1);
        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(shard0);
        return routingDataSource;
    }
}
