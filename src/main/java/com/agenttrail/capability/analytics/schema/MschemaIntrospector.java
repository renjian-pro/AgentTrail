package com.agenttrail.capability.analytics.schema;

import com.agenttrail.capability.analytics.config.AnalyticsSchemaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** JDBC schema 自省；catalog 从连接取得，采样失败只影响样例，不影响结构快照。 */
public class MschemaIntrospector {
    private static final Logger log = LoggerFactory.getLogger(MschemaIntrospector.class);
    private static final Set<String> SAMPLEABLE = Set.of("VARCHAR", "CHAR", "ENUM", "SET", "TINYTEXT", "TEXT", "MEDIUMTEXT");
    private final DataSource dataSource;
    private final AnalyticsSchemaProperties properties;
    private final Set<String> maskFields;

    public MschemaIntrospector(DataSource dataSource, AnalyticsSchemaProperties properties, Set<String> maskFields) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.maskFields = maskFields == null ? Set.of() : maskFields.stream()
                .map(MschemaIntrospector::lower).collect(Collectors.toUnmodifiableSet());
    }

    public Mschema introspect() {
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            if (catalog == null || catalog.isBlank()) throw new SQLException("分析连接没有当前 catalog");
            DatabaseMetaData meta = connection.getMetaData();
            Map<String, MutableTable> tables = new LinkedHashMap<>();
            try (ResultSet rs = meta.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (!excluded(name)) tables.put(name, new MutableTable(name, empty(rs.getString("REMARKS"))));
                }
            }
            for (MutableTable table : tables.values()) {
                Set<String> primaryKeys = new HashSet<>();
                try (ResultSet rs = meta.getPrimaryKeys(catalog, null, table.name)) {
                    while (rs.next()) primaryKeys.add(rs.getString("COLUMN_NAME"));
                }
                try (ResultSet rs = meta.getColumns(catalog, null, table.name, "%")) {
                    while (rs.next()) {
                        String name = rs.getString("COLUMN_NAME");
                        table.fields.add(new MutableField(name, empty(rs.getString("TYPE_NAME")),
                                empty(rs.getString("REMARKS")), primaryKeys.contains(name)));
                    }
                }
                try (ResultSet rs = meta.getImportedKeys(catalog, null, table.name)) {
                    while (rs.next()) {
                        String target = rs.getString("PKTABLE_NAME");
                        if (target != null && tables.containsKey(target)) {
                            table.foreignKeys.add(new Mschema.ForeignKeyDef(rs.getString("FKCOLUMN_NAME"),
                                    target, rs.getString("PKCOLUMN_NAME")));
                        }
                    }
                }
            }
            collectExamples(tables);
            return new Mschema(catalog, tables.values().stream().map(MutableTable::toRecord).toList());
        } catch (SQLException failure) {
            throw new IllegalStateException("M-Schema 自省失败: " + failure.getMessage(), failure);
        }
    }

    private void collectExamples(Map<String, MutableTable> tables) {
        Map<String, CompletableFuture<List<String>>> futures = new LinkedHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "analytics-schema-sample");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (MutableTable table : tables.values()) {
                for (MutableField field : table.fields) {
                    if (SAMPLEABLE.contains(field.type.toUpperCase(Locale.ROOT)) && !isMasked(table.name, field.name)) {
                        String key = table.name + "\u0000" + field.name;
                        futures.put(key, CompletableFuture.supplyAsync(() -> sample(table.name, field.name), executor));
                    }
                }
            }
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(Math.max(1, properties.getSampleTimeoutMs()));
            for (Map.Entry<String, CompletableFuture<List<String>>> entry : futures.entrySet()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    List<String> values = entry.getValue().get(remaining, TimeUnit.NANOSECONDS);
                    String[] parts = entry.getKey().split("\u0000", 2);
                    tables.get(parts[0]).field(parts[1]).examples = values;
                } catch (TimeoutException timeout) {
                    break;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException ignored) {
                    // 单列采样失败不影响其余列和整个 schema。
                }
            }
            collectCompletedExamples(tables, futures);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void collectCompletedExamples(Map<String, MutableTable> tables,
                                                 Map<String, CompletableFuture<List<String>>> futures) {
        for (Map.Entry<String, CompletableFuture<List<String>>> entry : futures.entrySet()) {
            CompletableFuture<List<String>> future = entry.getValue();
            if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) continue;
            try {
                String[] parts = entry.getKey().split("\u0000", 2);
                tables.get(parts[0]).field(parts[1]).examples = future.getNow(List.of());
            } catch (RuntimeException ignored) {
                // 采样只提供提示信息，任何单列异常都不能破坏结构快照。
            }
        }
    }

    private List<String> sample(String table, String column) {
        String quotedTable = quote(table);
        String quotedColumn = quote(column);
        String sql = "SELECT DISTINCT " + quotedColumn + " FROM " + quotedTable
                + " WHERE " + quotedColumn + " IS NOT NULL LIMIT "
                + Math.max(1, properties.getSampleLimit());
        try {
            return new JdbcTemplate(dataSource).query(sql, rs -> {
                List<String> values = new ArrayList<>();
                while (rs.next()) {
                    String value = rs.getString(1);
                    if (value == null || value.isBlank() || value.length() > 50
                            || value.contains("@") || value.contains("://")) continue;
                    if ((value.length() > 20 || isDateLike(value)) && values.stream()
                            .anyMatch(item -> item.length() > 20 || isDateLike(item))) continue;
                    values.add(value);
                }
                return values.stream().limit(Math.max(1, properties.getSampleLimit())).toList();
            });
        } catch (RuntimeException failure) {
            log.debug("示例值采样失败 {}.{}: {}", table, column, failure.getMessage());
            return List.of();
        }
    }

    private static boolean isDateLike(String value) {
        return value.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?:[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)?");
    }

    private boolean isMasked(String table, String column) {
        return maskFields.contains(lower(table + "." + column)) || maskFields.contains(lower(column));
    }

    private boolean excluded(String table) {
        if (table == null) return true;
        return properties.getExcludeTables().stream().filter(rule -> rule != null && !rule.isBlank())
                .map(String::trim).anyMatch(rule -> {
                    if (rule.endsWith("*")) return table.startsWith(rule.substring(0, rule.length() - 1));
                    return table.equalsIgnoreCase(rule);
                });
    }

    private static String quote(String identifier) {
        String tick = String.valueOf((char) 96);
        return tick + identifier.replace(tick, tick + tick) + tick;
    }
    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
    private static String empty(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class MutableTable {
        private final String name;
        private final String comment;
        private final List<MutableField> fields = new ArrayList<>();
        private final List<Mschema.ForeignKeyDef> foreignKeys = new ArrayList<>();
        private MutableTable(String name, String comment) {
            this.name = name;
            this.comment = comment;
        }
        private MutableField field(String name) {
            return fields.stream().filter(field -> field.name.equals(name)).findFirst().orElseThrow();
        }
        private Mschema.TableDef toRecord() {
            return new Mschema.TableDef(name, comment, fields.stream().map(MutableField::toRecord).toList(), foreignKeys);
        }
    }

    private static final class MutableField {
        private final String name;
        private final String type;
        private final String comment;
        private final boolean primaryKey;
        private List<String> examples = List.of();
        private MutableField(String name, String type, String comment, boolean primaryKey) {
            this.name = name;
            this.type = type;
            this.comment = comment;
            this.primaryKey = primaryKey;
        }
        private Mschema.FieldDef toRecord() {
            return new Mschema.FieldDef(name, type, comment, primaryKey, examples);
        }
    }
}
