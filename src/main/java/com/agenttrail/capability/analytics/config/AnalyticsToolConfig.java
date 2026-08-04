package com.agenttrail.capability.analytics.config;

import com.agenttrail.capability.analytics.AnalyticsToolProvider;
import com.agenttrail.capability.analytics.glossary.GlossaryCatalog;
import com.agenttrail.capability.analytics.glossary.LookupGlossaryTool;
import com.agenttrail.capability.analytics.schema.DescribeTablesTool;
import com.agenttrail.capability.analytics.schema.ListTablesTool;
import com.agenttrail.capability.analytics.schema.SchemaProvider;
import com.agenttrail.capability.analytics.sql.SqlSafetyGuard;
import com.agenttrail.capability.analytics.sql.ValidateSqlTool;
import com.agenttrail.capability.analytics.tools.CalculateTool;
import com.agenttrail.capability.analytics.tools.ExecuteSqlTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agenttrail.analytics.datasource", name = "enabled", havingValue = "true")
public class AnalyticsToolConfig {
    @Bean
    AnalyticsToolProvider analyticsToolProvider(SchemaProvider schemaProvider,
                                                GlossaryCatalog glossaryCatalog,
                                                SqlSafetyGuard safetyGuard,
                                                ExecuteSqlTool executeSqlTool) {
        return new AnalyticsToolProvider(List.of(
                ListTablesTool.callback(schemaProvider),
                DescribeTablesTool.callback(schemaProvider),
                LookupGlossaryTool.callback(glossaryCatalog),
                ValidateSqlTool.callback(safetyGuard),
                executeSqlTool.callback(),
                CalculateTool.callback()));
    }
}
