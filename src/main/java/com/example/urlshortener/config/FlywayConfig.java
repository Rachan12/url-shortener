package com.example.urlshortener.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(@Qualifier("shard0") DataSource shard0, @Qualifier("shard1") DataSource shard1) {
        return flyway -> {
            // Do nothing here to disable the default Flyway behavior
        };
    }

    @Bean(initMethod = "migrate")
    public Flyway flywayShard0(@Qualifier("shard0") DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("db/migration")
                .baselineOnMigrate(true)
                .load();
    }

    @Bean(initMethod = "migrate")
    public Flyway flywayShard1(@Qualifier("shard1") DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("db/migration")
                .baselineOnMigrate(true)
                .load();
    }
}
