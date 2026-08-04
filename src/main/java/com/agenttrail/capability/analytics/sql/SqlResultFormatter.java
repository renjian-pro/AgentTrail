package com.agenttrail.capability.analytics.sql;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.List;

public final class SqlResultFormatter {
    private SqlResultFormatter() { }

    public static String format(SqlResult result, int previewRows) {
        StringBuilder output = new StringBuilder("查询成功，共 ")
                .append(result.rows().size()).append(" 行（耗时 ")
                .append(result.elapsedMs()).append("ms）。\n");
        if (result.rows().isEmpty()) {
            return output.append("没有匹配数据。\n\n")
                    .append("空结果可能是过滤条件过严、时间口径不匹配、字段取值形态不同，或确实没有符合条件的数据。")
                    .append("建议先用 COUNT(*) 确认数量，或先调 lookup_glossary 查询时间口径，再逐步放宽条件。")
                    .toString();
        }
        int count = Math.min(Math.max(1, previewRows), result.rows().size());
        output.append("以下是前 ").append(count).append(" 行：\n\n");
        output.append('|');
        for (ColumnMeta column : result.columns()) {
            output.append(' ').append(escape(column.label())).append(" |");
        }
        output.append("\n|");
        for (int ignored = 0; ignored < result.columns().size(); ignored++) {
            output.append(" --- |");
        }
        output.append('\n');
        for (List<Object> row : result.rows().subList(0, count)) {
            output.append('|');
            for (Object value : row) {
                output.append(' ').append(escape(value(value))).append(" |");
            }
            output.append('\n');
        }
        if (result.truncated()) {
            output.append("\n结果超过行数上限，仅展示前 ").append(result.rows().size())
                    .append(" 行；不要从预览行手算总量。需要总量请改写为 COUNT/SUM/AVG 等聚合查询，")
                    .append("需要更多明细请带 ORDER BY 分页查询。");
        }
        return output.toString().strip();
    }

    private static String value(Object value) {
        if (value == null) return "NULL";
        if (value instanceof byte[] bytes) return "<binary " + bytes.length + " bytes>";
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        if (value instanceof TemporalAccessor) return value.toString();
        return String.valueOf(value);
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
