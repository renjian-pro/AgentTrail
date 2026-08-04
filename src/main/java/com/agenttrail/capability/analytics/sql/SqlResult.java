package com.agenttrail.capability.analytics.sql;

import java.util.List;

public record SqlResult(List<ColumnMeta> columns,
                        List<List<Object>> rows,
                        boolean truncated,
                        long elapsedMs,
                        String sql) {
    public SqlResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
        sql = sql == null ? "" : sql;
    }
}
