package com.agenttrail.capability.analytics.sql;

import com.agenttrail.capability.analytics.config.AnalyticsExecutionProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

/** 结果集只读、查询超时和 N+1 截断都在 JDBC 层生效。 */
public class ReadOnlyQueryRunner {
    private final DataSource dataSource;
    private final AnalyticsExecutionProperties properties;

    public ReadOnlyQueryRunner(DataSource dataSource, AnalyticsExecutionProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    public SqlResult query(String sql) throws Exception {
        long started = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            try {
                connection.setReadOnly(true);
            } catch (Exception ignored) {
                // 数据库驱动对 setReadOnly 的支持不一致，账号只读权限仍是最终边界。
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
                int maxRows = Math.max(1, properties.getMaxRows());
                statement.setMaxRows(maxRows + 1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData meta = resultSet.getMetaData();
                    List<ColumnMeta> columns = new ArrayList<>();
                    for (int index = 1; index <= meta.getColumnCount(); index++) {
                        columns.add(new ColumnMeta(meta.getColumnLabel(index),
                                meta.getColumnName(index), meta.getTableName(index)));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (resultSet.next()) {
                        if (rows.size() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>();
                        for (int index = 1; index <= meta.getColumnCount(); index++) {
                            row.add(resultSet.getObject(index));
                        }
                        rows.add(row);
                    }
                    return new SqlResult(columns, rows, truncated,
                            (System.nanoTime() - started) / 1_000_000, sql);
                }
            }
        }
    }
}
