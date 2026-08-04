package com.agenttrail.capability.analytics.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 分析账号可见的数据库元数据快照；只读对象，可安全序列化进缓存和元数据接口。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Mschema(String database, List<TableDef> tables) {
    public Mschema {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public record TableDef(String name, String comment, List<FieldDef> fields, List<ForeignKeyDef> foreignKeys) {
        public TableDef {
            comment = comment == null ? "" : comment;
            fields = fields == null ? List.of() : List.copyOf(fields);
            foreignKeys = foreignKeys == null ? List.of() : List.copyOf(foreignKeys);
        }
    }

    public record FieldDef(String name, String type, String comment, boolean primaryKey, List<String> examples) {
        public FieldDef {
            type = type == null ? "" : type;
            comment = comment == null ? "" : comment;
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }

    public record ForeignKeyDef(String fromColumn, String toTable, String toColumn) { }
}
