package com.agenttrail.capability.analytics.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 分析专用连接池。虽然 jdbc-url 通常和主库相同，账号不同仍然要求独立连接池：
 * 主库池需要写会话/追踪表，分析池只能读显式授权的业务表；同时慢查询不能占满主库写入连接。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agenttrail.analytics.datasource", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AnalyticsDataSourceProperties.class)
public class AnalyticsDataSourceConfig {

    @Bean(name = "analyticsDataSource", destroyMethod = "close")
    public DataSource analyticsDataSource(AnalyticsDataSourceProperties properties) {
        if (properties.getJdbcUrl() == null || properties.getJdbcUrl().isBlank()) {
            throw new IllegalStateException("分析数据源已启用，但 agenttrail.analytics.datasource.jdbc-url 为空");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getJdbcUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(Math.max(1, Math.min(properties.getMaximumPoolSize(), 100)));
        config.setConnectionTimeout(Math.max(250, properties.getConnectionTimeoutMs()));
        config.setReadOnly(true);
        config.setPoolName("analytics-pool");
        return new HikariDataSource(config);
    }
}
