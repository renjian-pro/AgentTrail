package com.agenttrail.capability.analytics.sql;

import com.agenttrail.capability.analytics.config.AnalyticsExecutionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.Locale;

public class ExplainPrecheckService {
    private static final Logger log = LoggerFactory.getLogger(ExplainPrecheckService.class);
    private final DataSource dataSource;
    private final AnalyticsExecutionProperties properties;

    public ExplainPrecheckService(DataSource dataSource, AnalyticsExecutionProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    public PrecheckResult precheck(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.max(1, properties.getExplainTimeoutSeconds()));
            try (ResultSet resultSet = statement.executeQuery("EXPLAIN " + sql)) {
                long estimated = 1;
                while (resultSet.next()) {
                    int column = findRowsColumn(resultSet);
                    if (column > 0) {
                        long rows = Math.max(1, resultSet.getLong(column));
                        if (estimated > Long.MAX_VALUE / rows) {
                            estimated = Long.MAX_VALUE;
                        } else {
                            estimated *= rows;
                        }
                    }
                }
                if (estimated > properties.getMaxEstimatedRows() && !isAggregate(sql)) {
                    return PrecheckResult.block("预计扫描组合行数 " + estimated
                            + " 超过上限 " + properties.getMaxEstimatedRows()
                            + "，请增加过滤条件或改写为聚合查询");
                }
                return PrecheckResult.pass();
            }
        } catch (SQLTimeoutException timeout) {
            return PrecheckResult.block("EXPLAIN 预检超时，请简化查询");
        } catch (SQLException failure) {
            log.debug("EXPLAIN 预检异常，交给执行层兜底: {}", failure.getMessage());
            return PrecheckResult.pass();
        }
    }

    private static int findRowsColumn(ResultSet resultSet) {
        try {
            ResultSetMetaDataAdapter meta = new ResultSetMetaDataAdapter(resultSet);
            for (int index = 1; index <= meta.count(); index++) {
                if ("rows".equalsIgnoreCase(meta.label(index))) {
                    return index;
                }
            }
        } catch (SQLException ignored) {
            return -1;
        }
        return -1;
    }

    private static boolean isAggregate(String sql) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        return normalized.contains(" group by ")
                || normalized.matches("(?s).*\\b(count|sum|avg|min|max)\\s*\\(.*");
    }

    public record PrecheckResult(boolean allowed, String reason) {
        public static PrecheckResult pass() { return new PrecheckResult(true, ""); }
        public static PrecheckResult block(String reason) { return new PrecheckResult(false, reason); }
    }

    private record ResultSetMetaDataAdapter(ResultSet resultSet) {
        private int count() throws SQLException { return resultSet.getMetaData().getColumnCount(); }
        private String label(int index) throws SQLException { return resultSet.getMetaData().getColumnLabel(index); }
    }
}
