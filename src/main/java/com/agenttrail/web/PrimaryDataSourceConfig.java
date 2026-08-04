package com.agenttrail.web;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Boot's auto-configured "dataSource" bean backs off via {@code @ConditionalOnMissingBean(DataSource.class)}
 * the moment any other {@code DataSource} bean exists in the context — which
 * {@code AnalyticsDataSourceConfig}'s {@code analyticsDataSource} bean does whenever
 * {@code agenttrail.analytics.datasource.enabled=true}. Without an explicit bean here, every
 * {@code @Qualifier("dataSource")} injection point in the app (auth stores, file/memory/session/
 * trace/ppt persistence, ...) breaks app-wide the moment analytics gets turned on.
 */
@Configuration(proxyBeanMethods = false)
public class PrimaryDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    // DataSourceProperties (not raw @ConfigurationProperties on the pool bean) is what actually
    // knows how to turn "spring.datasource.url" into whatever the target pool implementation
    // calls it — Hikari's own setter is setJdbcUrl(), not setUrl(), so binding the prefix directly
    // onto a bare HikariDataSource silently leaves jdbcUrl empty and fails pool validation.
    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
