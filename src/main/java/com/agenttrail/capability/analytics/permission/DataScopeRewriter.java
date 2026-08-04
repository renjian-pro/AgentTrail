package com.agenttrail.capability.analytics.permission;

import com.agenttrail.sys.datascope.DataScope;
import com.agenttrail.sys.datascope.DataScopeContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitor;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 递归把数据权限写入 AST；外连接右表条件必须进 ON，解析失败不得放行原 SQL。 */
public class DataScopeRewriter {
    private final PermissionRuleRegistry registry;

    public DataScopeRewriter(PermissionRuleRegistry registry) {
        this.registry = registry;
    }

    public String rewrite(String sql, DataScopeContext context) {
        if (context == null) {
            throw new DataScopeRewriteException("DataScopeContext 不能为空，拒绝执行");
        }
        if (context.scope() == DataScope.ALL) {
            return sql;
        }
        if (context.scope() != DataScope.SELF && context.deptIds().isEmpty()) {
            throw new DataScopeRewriteException("用户没有任何可见部门，拒绝执行: userId=" + context.userId());
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select)) {
                return sql;
            }
            Set<String> cteNames = collectCteNames(select);
            RewriteVisitor visitor = new RewriteVisitor(context, cteNames);
            select.accept((SelectVisitor<Void>) visitor, null);
            return statement.toString();
        } catch (DataScopeRewriteException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new DataScopeRewriteException("数据权限改写失败，拒绝执行: " + failure.getMessage(), failure);
        }
    }

    private Set<String> collectCteNames(Select select) {
        Set<String> names = new HashSet<>();
        if (select.getWithItemsList() != null) {
            select.getWithItemsList().forEach(item -> names.add(item.getUnquotedAliasName().toLowerCase(Locale.ROOT)));
        }
        return names;
    }

    private final class RewriteVisitor extends TablesNamesFinder<Void> {
        private final DataScopeContext context;
        private final Set<String> cteNames;

        private RewriteVisitor(DataScopeContext context, Set<String> cteNames) {
            this.context = context;
            this.cteNames = cteNames;
            init(true);
        }

        @Override
        public <S> Void visit(PlainSelect select, S ignored) {
            injectFrom(select);
            return super.visit(select, ignored);
        }

        private void injectFrom(PlainSelect select) {
            if (select.getFromItem() instanceof Table table) {
                injectWhere(select, table);
            }
            for (Join join : select.getJoins() == null ? List.<Join>of() : select.getJoins()) {
                if (join.getRightItem() instanceof Table table) {
                    injectJoin(select, join, table);
                }
            }
        }

        private void injectWhere(PlainSelect select, Table table) {
            if (isCte(table)) {
                return;
            }
            Expression condition = registry.ruleFor(table.getName()).buildCondition(table, context);
            if (condition != null) {
                select.setWhere(and(select.getWhere(), condition));
            }
        }

        private void injectJoin(PlainSelect select, Join join, Table table) {
            if (isCte(table)) {
                return;
            }
            Expression condition = registry.ruleFor(table.getName()).buildCondition(table, context);
            if (condition == null) {
                return;
            }
            if (join.isLeft() || join.isRight() || join.isFull()) {
                Expression current = join.getOnExpression();
                if (current == null && join.getOnExpressions() != null && !join.getOnExpressions().isEmpty()) {
                    current = join.getOnExpressions().stream().reduce(DataScopeRewriter::and).orElse(null);
                }
                join.setOnExpressions(List.of(and(current, condition)));
            } else {
                select.setWhere(and(select.getWhere(), condition));
            }
        }

        private boolean isCte(Table table) {
            return cteNames.contains(table.getName().toLowerCase(Locale.ROOT));
        }
    }

    private static Expression and(Expression existing, Expression condition) {
        if (existing == null) {
            return condition;
        }
        Parenthesis parenthesis = new Parenthesis();
        parenthesis.add(existing);
        return new AndExpression(parenthesis, condition);
    }
}
