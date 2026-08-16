package com.agenttrail.capability.analytics.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SqlResult(List<ColumnMeta> columns,
                        List<List<Object>> rows,
                        boolean truncated,
                        long elapsedMs,
                        String sql) {
    public SqlResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : rows.stream().map(SqlResult::unmodifiableRowAllowingNulls).toList();
        sql = sql == null ? "" : sql;
    }

    /**
     * 行数据不能用 {@code List.copyOf}：它对 null 元素直接抛 {@code NullPointerException}，
     * 而 SQL NULL 是再正常不过的取值——{@code LEFT JOIN} 没匹配上的那一侧、{@code SELECT *}
     * 撞上可空列（{@code rental.return_date}）都会产生它。之前每次查到 NULL，整个 execute_sql
     * 就返回一句"查询执行失败：NullPointerException"，模型只能换个写法反复重试；跑批里
     * sql-005（列出租赁明细）和一批 LEFT JOIN 的用例长期挂在这上面。
     */
    private static List<Object> unmodifiableRowAllowingNulls(List<Object> row) {
        return Collections.unmodifiableList(new ArrayList<>(row));
    }
}
