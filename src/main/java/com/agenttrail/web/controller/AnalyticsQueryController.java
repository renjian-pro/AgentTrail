package com.agenttrail.web.controller;

import com.agenttrail.capability.analytics.glossary.GlossaryCatalog;
import com.agenttrail.capability.analytics.glossary.GlossaryEntry;
import com.agenttrail.capability.analytics.schema.Mschema;
import com.agenttrail.capability.analytics.schema.SchemaProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 给分析前端提供只读 Schema 与业务术语浏览数据，不复用管理端权限。 */
@RestController
@RequestMapping("/api/analytics")
@ConditionalOnProperty(prefix = "agenttrail.analytics.datasource", name = "enabled", havingValue = "true")
public class AnalyticsQueryController {
    private final SchemaProvider schemaProvider;
    private final GlossaryCatalog glossaryCatalog;

    public AnalyticsQueryController(SchemaProvider schemaProvider, GlossaryCatalog glossaryCatalog) {
        this.schemaProvider = schemaProvider;
        this.glossaryCatalog = glossaryCatalog;
    }

    @GetMapping("/schema")
    public Mschema schema() {
        return schemaProvider.schema();
    }

    @GetMapping("/glossary")
    public List<GlossaryEntry> glossary() {
        return glossaryCatalog.all();
    }
}
