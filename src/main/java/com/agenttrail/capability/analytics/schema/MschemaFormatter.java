package com.agenttrail.capability.analytics.schema;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 将元数据快照转换为紧凑 M-Schema 文本；不接触数据库，便于稳定单测。 */
public final class MschemaFormatter {
    private MschemaFormatter() { }

    public static String formatTableList(Mschema schema) {
        StringBuilder out = new StringBuilder("# Database: ").append(schema.database()).append("\n\n");
        for (Mschema.TableDef table : schema.tables()) {
            out.append("## ").append(table.name());
            if (!table.comment().isBlank()) out.append("  -- ").append(table.comment());
            List<String> related = table.foreignKeys().stream().map(Mschema.ForeignKeyDef::toTable).distinct().toList();
            if (!related.isEmpty()) out.append("  (关联: ").append(String.join(", ", related)).append(")");
            out.append('\n');
        }
        appendForeignKeys(out, schema, schema.tables().stream().map(Mschema.TableDef::name).collect(Collectors.toSet()));
        return out.toString().strip();
    }

    public static String formatTables(Mschema schema, List<String> tableNames) {
        Set<String> requested = tableNames.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(LinkedHashSet::new));
        StringBuilder out = new StringBuilder("# Database: ").append(schema.database()).append("\n\n");
        Set<String> rendered = new LinkedHashSet<>();
        for (Mschema.TableDef table : schema.tables()) {
            if (!requested.contains(table.name().toLowerCase(Locale.ROOT))) continue;
            rendered.add(table.name().toLowerCase(Locale.ROOT));
            out.append("## ").append(table.name());
            if (!table.comment().isBlank()) out.append("  -- ").append(table.comment());
            out.append('\n');
            for (Mschema.FieldDef field : table.fields()) {
                out.append('(').append(field.name()).append(": ").append(field.type());
                if (field.primaryKey()) out.append(", 主键");
                if (!field.comment().isBlank()) out.append(", ").append(field.comment());
                if (!field.examples().isEmpty()) out.append(", Examples: [").append(String.join(", ", field.examples())).append(']');
                out.append(")\n");
            }
            out.append('\n');
        }
        appendForeignKeys(out, schema, rendered);
        return out.toString().strip();
    }

    private static void appendForeignKeys(StringBuilder out, Mschema schema, Set<String> included) {
        List<String> keys = schema.tables().stream()
                .filter(table -> included.contains(table.name().toLowerCase(Locale.ROOT)))
                .flatMap(table -> table.foreignKeys().stream().map(fk -> table.name() + "." + fk.fromColumn()
                        + " -> " + fk.toTable() + "." + fk.toColumn()))
                .toList();
        if (!keys.isEmpty()) out.append("\n# Foreign keys\n").append(String.join("\n", keys));
    }
}
