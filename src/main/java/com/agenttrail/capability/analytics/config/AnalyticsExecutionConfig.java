package com.agenttrail.capability.analytics.config;

import com.agenttrail.capability.analytics.mask.SensitiveFilter;
import com.agenttrail.capability.analytics.permission.DataScopeRewriter;
import com.agenttrail.capability.analytics.permission.DefaultPermissionRule;
import com.agenttrail.capability.analytics.permission.NoPermissionRule;
import com.agenttrail.capability.analytics.permission.PermissionRuleRegistry;
import com.agenttrail.capability.analytics.sql.ExplainPrecheckService;
import com.agenttrail.capability.analytics.sql.ReadOnlyQueryRunner;
import com.agenttrail.capability.analytics.sql.SqlSafetyGuard;
import com.agenttrail.capability.analytics.tools.ExecuteSqlTool;
import com.agenttrail.sys.datascope.DataScopeResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agenttrail.analytics.datasource", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({AnalyticsSqlProperties.class, AnalyticsExecutionProperties.class})
public class AnalyticsExecutionConfig {
    private static final Set<String> MASTER_TABLES = Set.of(
            "film", "film_actor", "film_category", "film_text", "category", "language",
            "actor", "customer", "address", "city", "country", "inventory", "dim_dept");

    @Bean
    SqlSafetyGuard sqlSafetyGuard(AnalyticsSqlProperties properties) {
        return new SqlSafetyGuard(properties.getMaxJoins(), properties.getMaxRows());
    }

    @Bean
    PermissionRuleRegistry permissionRuleRegistry() {
        return new PermissionRuleRegistry(
                new DefaultPermissionRule(),
                new NoPermissionRule(MASTER_TABLES));
    }

    @Bean
    DataScopeRewriter dataScopeRewriter(PermissionRuleRegistry registry) {
        return new DataScopeRewriter(registry);
    }

    @Bean
    ExplainPrecheckService explainPrecheckService(@Qualifier("analyticsDataSource") DataSource analyticsDataSource,
                                                  AnalyticsExecutionProperties properties) {
        return new ExplainPrecheckService(analyticsDataSource, properties);
    }

    @Bean
    ReadOnlyQueryRunner readOnlyQueryRunner(@Qualifier("analyticsDataSource") DataSource analyticsDataSource,
                                            AnalyticsExecutionProperties properties) {
        return new ReadOnlyQueryRunner(analyticsDataSource, properties);
    }

    @Bean
    SensitiveFilter sensitiveFilter(AnalyticsMaskProperties properties) {
        return new SensitiveFilter(properties.getMaskFields());
    }

    @Bean
    ExecuteSqlTool executeSqlTool(SqlSafetyGuard safetyGuard,
                                  DataScopeResolver scopeResolver,
                                  DataScopeRewriter scopeRewriter,
                                  ExplainPrecheckService explain,
                                  ReadOnlyQueryRunner runner,
                                  SensitiveFilter sensitiveFilter,
                                  AnalyticsExecutionProperties properties) {
        return new ExecuteSqlTool(safetyGuard, scopeResolver, scopeRewriter, explain,
                runner, sensitiveFilter, properties);
    }

    @Bean
    ToolCallback executeSqlToolCallback(ExecuteSqlTool tool) {
        return tool.callback();
    }
}
