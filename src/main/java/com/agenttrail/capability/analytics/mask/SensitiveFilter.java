package com.agenttrail.capability.analytics.mask;

import com.agenttrail.capability.analytics.sql.ColumnMeta;
import com.agenttrail.capability.analytics.sql.SqlResult;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class SensitiveFilter {
    private static final String MASK = "********";
    private final Set<String> fields;
    private final Set<String> columns;

    public SensitiveFilter(Iterable<String> configured) {
        Set<String> full = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (String value : configured == null ? Set.<String>of() : configured) {
            if (value == null || value.isBlank()) continue;
            String normalized = lower(value);
            full.add(normalized);
            int dot = normalized.lastIndexOf('.');
            names.add(dot < 0 ? normalized : normalized.substring(dot + 1));
        }
        fields = Set.copyOf(full);
        columns = Set.copyOf(names);
    }

    public SqlResult mask(SqlResult result) {
        return mask(result, result.sql());
    }

    public SqlResult mask(SqlResult result, String sql) {
        Set<Integer> sensitiveExpressions = sensitiveExpressionIndexes(sql);
        java.util.List<java.util.List<Object>> maskedRows = result.rows().stream().map(row -> {
            java.util.List<Object> copy = new java.util.ArrayList<>(row);
            for (int index = 0; index < result.columns().size(); index++) {
                if (sensitiveExpressions.contains(index) || isSensitive(result.columns().get(index))) {
                    copy.set(index, MASK);
                }
            }
            return copy;
        }).toList();
        return new SqlResult(result.columns(), maskedRows, result.truncated(),
                result.elapsedMs(), result.sql());
    }

    private boolean isSensitive(ColumnMeta column) {
        if (!blank(column.tableName())
                && fields.contains(lower(column.tableName() + "." + column.columnName()))) {
            return true;
        }
        return columns.contains(lower(column.columnName()))
                || columns.contains(lower(column.label()));
    }

    private Set<Integer> sensitiveExpressionIndexes(String sql) {
        if (sql == null || sql.isBlank()) return Set.of();
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select) || !(select.getPlainSelect() instanceof PlainSelect plain)
                    || plain.getSelectItems() == null) return Set.of();
            Set<Integer> indexes = new HashSet<>();
            for (int index = 0; index < plain.getSelectItems().size(); index++) {
                var expression = plain.getSelectItems().get(index).getExpression();
                if (expression == null) continue;
                final boolean[] found = {false};
                expression.accept(new ExpressionVisitorAdapter<Void>() {
                    @Override
                    public <S> Void visit(Column column, S ignored) {
                        String table = column.getTableName();
                        String name = column.getColumnName();
                        if (isSensitive(new ColumnMeta("", name, table))) found[0] = true;
                        return super.visit(column, ignored);
                    }
                }, null);
                if (found[0]) indexes.add(index);
            }
            return indexes;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
