package com.agenttrail.capability.analytics.sql;

import java.sql.SQLException;

public final class SqlErrorClassifier {
    private SqlErrorClassifier() { }

    public enum Kind { TRANSIENT, SCHEMA, SYNTAX, PERMISSION, FATAL }

    public static boolean isTransient(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || "40001".equals(state))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public static String message(Throwable failure) {
        SQLException sql = findSqlException(failure);
        if (sql != null) {
            String state = sql.getSQLState();
            if (state == null || state.isBlank()) {
                return "数据库返回未知错误，无法安全重试：" + detail(sql);
            }
            if (state.startsWith("42S02") || state.startsWith("42S22")) {
                return "表或字段不存在，请先调用 describe_tables 确认 Schema：" + detail(sql);
            }
            if (state.startsWith("42000")) {
                return "SQL 语法或执行权限有误，请检查语法和可访问表：" + detail(sql);
            }
            if (state.startsWith("28")) {
                return "分析账号无权访问该表或字段，请检查只读授权：" + detail(sql);
            }
        }
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return failure.getClass().getSimpleName();
    }

    public static Kind classify(SQLException failure) {
        if (failure == null) return Kind.FATAL;
        String state = failure.getSQLState();
        if (state != null && (state.startsWith("08") || "40001".equals(state))) return Kind.TRANSIENT;
        if (state != null && (state.startsWith("42S02") || state.startsWith("42S22"))) return Kind.SCHEMA;
        if (state != null && state.startsWith("42000")) return Kind.SYNTAX;
        if (state != null && state.startsWith("28")) return Kind.PERMISSION;
        return Kind.FATAL;
    }

    private static SQLException findSqlException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql) return sql;
            current = current.getCause();
        }
        return null;
    }

    private static String detail(SQLException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
