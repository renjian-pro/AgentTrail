package com.agenttrail.capability.analytics.config;

import com.agenttrail.capability.analytics.schema.DescribeTablesTool;
import com.agenttrail.capability.analytics.schema.ListTablesTool;
import com.agenttrail.capability.analytics.schema.MschemaCacheService;
import com.agenttrail.capability.analytics.schema.MschemaIntrospector;
import com.agenttrail.capability.analytics.schema.MschemaSchemaProvider;
import com.agenttrail.capability.analytics.schema.SchemaProvider;
import com.agenttrail.loop.task.RedisTaskLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 分析数据源启用后装配 M-Schema 读取和查询工具。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agenttrail.analytics.datasource", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({AnalyticsSchemaProperties.class, AnalyticsMaskProperties.class})
public class AnalyticsSchemaConfig {

    @Bean
    MschemaIntrospector mschemaIntrospector(@Qualifier("analyticsDataSource") DataSource analyticsDataSource,
                                             AnalyticsSchemaProperties properties,
                                             AnalyticsMaskProperties masks) {
        Set<String> maskFields = Set.copyOf(masks.getMaskFields());
        return new MschemaIntrospector(analyticsDataSource, properties, maskFields);
    }

    @Bean
    MschemaCacheService mschemaCacheService(MschemaIntrospector introspector,
                                             ObjectMapper objectMapper,
                                             AnalyticsSchemaProperties properties,
                                             ObjectProvider<RedissonClient> redissonProvider) {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        RedisTaskLock lock = redisson == null
                ? null
                : new RedisTaskLock(redisson, "analytics-" + UUID.randomUUID(), Duration.ofMinutes(2));
        return new MschemaCacheService(introspector, objectMapper, properties, redisson, lock);
    }

    @Bean
    SchemaProvider schemaProvider(MschemaCacheService cache) {
        return new MschemaSchemaProvider(cache);
    }

    @Bean
    @ConditionalOnBean(SchemaProvider.class)
    ToolCallback listTablesTool(SchemaProvider provider) {
        return ListTablesTool.callback(provider);
    }

    @Bean
    @ConditionalOnBean(SchemaProvider.class)
    ToolCallback describeTablesTool(SchemaProvider provider) {
        return DescribeTablesTool.callback(provider);
    }
}
