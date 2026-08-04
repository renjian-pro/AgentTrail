package com.agenttrail.capability.analytics.sql;

import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitor;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.List;
import java.util.Locale;

/** 只在 AST 上做 SQL 安全校验和 LIMIT 规范化，解析失败一律拒绝。 */
public class SqlSafetyGuard {
    private final int maxJoins;
    private final int maxRows;

    public SqlSafetyGuard(int maxJoins, int maxRows) {
        this.maxJoins = Math.max(0, maxJoins);
        this.maxRows = Math.max(1, maxRows);
    }

    public ValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.reject("SQL 不能为空");
        }
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements == null || statements.getStatements().size() != 1) {
                int count = statements == null ? 0 : statements.getStatements().size();
                return ValidationResult.reject("只允许单条 SQL，检测到 " + count + " 条语句");
            }
            Statement statement = statements.getStatements().get(0);
            if (!(statement instanceof Select select)) {
                return ValidationResult.reject("只允许 SELECT/WITH 查询，检测到 "
                        + statement.getClass().getSimpleName());
            }
            String raw = sql.toLowerCase(Locale.ROOT);
            if (raw.contains("into outfile") || raw.contains("into dumpfile")) {
                return ValidationResult.reject("禁止 INTO OUTFILE/DUMPFILE 文件写出");
            }
            if (raw.contains("@@")) {
                return ValidationResult.reject("禁止访问 MySQL 系统变量");
            }
            if (select.getForMode() != null || select.getForUpdateTable() != null
                    || raw.matches("(?s).*\\bfor\\s+update\\b.*")) {
                return ValidationResult.reject("禁止 FOR UPDATE 加锁查询");
            }
            ShapeFinder shape = new ShapeFinder(maxJoins);
            select.accept((SelectVisitor<Void>) shape, null);
            DangerousFunctionFinder functions = new DangerousFunctionFinder();
            select.accept((SelectVisitor<Void>) functions, null);
            if (!functions.hits().isEmpty()) {
                return ValidationResult.reject("检测到禁止的函数：" + String.join(", ", functions.hits()));
            }
            normalizeLimit(select);
            return ValidationResult.pass(select.toString());
        } catch (Exception failure) {
            return ValidationResult.reject("SQL 无法被安全解析：" + safeMessage(failure));
        }
    }

    private void normalizeLimit(Select select) {
        Limit limit = select.getLimit();
        if (limit == null) {
            select.setLimit(new Limit().withRowCount(new LongValue(maxRows)));
            return;
        }
        if (limit.isLimitAll() || limit.getRowCount() == null) {
            limit.setLimitAll(false);
            limit.setRowCount(new LongValue(maxRows));
            return;
        }
        try {
            long requested = Long.parseLong(limit.getRowCount().toString());
            if (requested > maxRows) {
                limit.setRowCount(new LongValue(maxRows));
            }
        } catch (NumberFormatException ignored) {
            limit.setRowCount(new LongValue(maxRows));
        }
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static final class ShapeFinder extends TablesNamesFinder<Void> {
        private final int maxJoins;

        private ShapeFinder(int maxJoins) {
            this.maxJoins = maxJoins;
            init(true);
        }

        @Override
        public <S> Void visit(PlainSelect select, S context) {
            List<Join> joins = select.getJoins() == null ? List.of() : select.getJoins();
            if (joins.size() > maxJoins) {
                throw new SafetyViolation("JOIN 数量 " + joins.size() + " 超过上限 " + maxJoins);
            }
            for (Join join : joins) {
                if (join.isCross()) {
                    throw new SafetyViolation("禁止 CROSS JOIN 笛卡尔积");
                }
                boolean hasCondition = join.getOnExpression() != null
                        || (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty());
                if (!join.isSimple() && !hasCondition) {
                    throw new SafetyViolation("JOIN 缺少 ON/USING 条件");
                }
            }
            if (select.getOffset() != null
                    && (select.getOrderByElements() == null || select.getOrderByElements().isEmpty())) {
                throw new SafetyViolation("使用 OFFSET 分页时必须带 ORDER BY");
            }
            return super.visit(select, context);
        }
    }

    private static final class SafetyViolation extends RuntimeException {
        private SafetyViolation(String message) {
            super(message);
        }
    }
}
