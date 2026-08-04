package com.agenttrail.capability.analytics.schema;

import java.util.List;

/** 给工具提供模型友好的 Schema 文本；结构化接口直接使用 Mschema 快照。 */
public interface SchemaProvider {
    Mschema schema();
    String listTables();
    String describeTables(List<String> tableNames);
}
