package com.agenttrail.capability.analytics.sql;

public record ColumnMeta(String label, String columnName, String tableName) {
    public ColumnMeta {
        label = label == null ? "" : label;
        columnName = columnName == null ? "" : columnName;
        tableName = tableName == null ? "" : tableName;
    }
}
