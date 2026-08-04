package com.agenttrail.capability.analytics.schema;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 对业务工具暴露的 Schema 查询边界。 */
public class MschemaSchemaProvider implements SchemaProvider {
    private final MschemaCacheService cache;

    public MschemaSchemaProvider(MschemaCacheService cache) {
        this.cache = cache;
    }

    @Override
    public Mschema schema() {
        return cache.get();
    }

    @Override
    public String listTables() {
        return MschemaFormatter.formatTableList(schema());
    }

    @Override
    public String describeTables(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return "Error: 至少指定一个表名";
        }
        Mschema snapshot = schema();
        Set<String> known = snapshot.tables().stream()
                .map(table -> table.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<String> missing = tableNames.stream()
                .filter(name -> name == null || !known.contains(name.toLowerCase(Locale.ROOT)))
                .toList();
        if (!missing.isEmpty()) {
            return "Error: 未找到分析表：" + String.join("、", missing);
        }
        return MschemaFormatter.formatTables(snapshot, tableNames);
    }
}
