package com.agenttrail.capability.analytics.sql;

import com.agenttrail.capability.analytics.mask.SensitiveFilter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlExecutionSupportTest {
    @Test
    void formatsEmptyAndTruncatedResultsWithNextStepGuidance() {
        SqlResult empty = new SqlResult(List.of(new ColumnMeta("total", "total", "")), List.of(), false, 4, "SELECT 1");
        assertThat(SqlResultFormatter.format(empty, 20)).contains("没有匹配数据", "COUNT(*)", "lookup_glossary");

        SqlResult truncated = new SqlResult(List.of(new ColumnMeta("amount", "amount", "")),
                List.of(List.of(new BigDecimal("10.5000"))), true, 8, "SELECT amount");
        assertThat(SqlResultFormatter.format(truncated, 20))
                .contains("10.5", "不要从预览行手算总量", "ORDER BY");
    }

    /**
     * SQL NULL 必须能原样穿过 {@link SqlResult}。这里回归的是一条踩了很久的坑：行数据当初用
     * {@code List.copyOf} 做不可变拷贝，而它对 null 元素直接抛 NPE。于是任何一条查出 NULL 的
     * 结果——{@code LEFT JOIN} 没匹配上的那一侧、{@code SELECT *} 撞上可空列——都会让 execute_sql
     * 整个返回"查询执行失败：NullPointerException"，模型只看得到一句无从下手的错误。
     */
    @Test
    void keepsSqlNullsInsteadOfBlowingUpOnThem() {
        SqlResult withNulls = new SqlResult(
                List.of(new ColumnMeta("dept_name", "dept_name", "dim_dept"),
                        new ColumnMeta("revenue", "amount", "payment")),
                List.of(java.util.Arrays.asList(null, new BigDecimal("10.5")),
                        java.util.Arrays.asList("销售一部", null)),
                false, 3, "SELECT d.dept_name, SUM(p.amount) FROM payment p LEFT JOIN dim_dept d ...");

        assertThat(withNulls.rows()).hasSize(2);
        assertThat(withNulls.rows().get(0).get(0)).isNull();
        assertThat(withNulls.rows().get(1).get(1)).isNull();
        assertThat(SqlResultFormatter.format(withNulls, 20)).contains("销售一部");
    }

    @Test
    void classifiesSchemaSyntaxPermissionAndTransientFailures() {
        assertThat(SqlErrorClassifier.classify(new SQLException("missing", "42S02")))
                .isEqualTo(SqlErrorClassifier.Kind.SCHEMA);
        assertThat(SqlErrorClassifier.classify(new SQLException("bad syntax", "42000")))
                .isEqualTo(SqlErrorClassifier.Kind.SYNTAX);
        assertThat(SqlErrorClassifier.classify(new SQLException("denied", "28000")))
                .isEqualTo(SqlErrorClassifier.Kind.PERMISSION);
        assertThat(SqlErrorClassifier.isTransient(new SQLException("deadlock", "40001"))).isTrue();
        assertThat(SqlErrorClassifier.message(new SQLException("missing rental", "42S02")))
                .contains("describe_tables");
    }

    @Test
    void masksAliasesAndSensitiveExpressionsBySourceColumn() {
        SensitiveFilter filter = new SensitiveFilter(List.of("user_profile.id_card"));
        SqlResult result = new SqlResult(
                List.of(new ColumnMeta("code", "id_card", "user_profile"),
                        new ColumnMeta("combined", "", "")),
                List.of(List.of("110101", "110101")), false, 1,
                "SELECT id_card AS code, CONCAT(id_card, '') AS combined FROM user_profile");

        SqlResult masked = filter.mask(result);
        assertThat(masked.rows().get(0)).containsExactly("********", "********");
    }
}
