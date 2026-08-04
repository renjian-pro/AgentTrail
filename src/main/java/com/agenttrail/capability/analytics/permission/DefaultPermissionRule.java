package com.agenttrail.capability.analytics.permission;

import com.agenttrail.sys.datascope.DataScope;
import com.agenttrail.sys.datascope.DataScopeContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import java.util.Set;

/** 业务事实表按 user_id 或 dept_id 限制；部门树展开由 DataScopeResolver 完成。 */
public final class DefaultPermissionRule implements PermissionRule {
    private static final Set<String> TABLES = Set.of("rental", "payment", "user_profile");

    @Override
    public Set<String> managedTables() {
        return TABLES;
    }

    @Override
    public Expression buildCondition(Table table, DataScopeContext context) {
        if (context.scope() == DataScope.ALL) {
            return null;
        }
        String qualifier = table.getAlias() == null ? table.getName() : table.getAlias().getName();
        if (context.scope() == DataScope.SELF) {
            return condition(qualifier + ".user_id = " + numeric(context.userId()));
        }
        if (context.deptIds().isEmpty()) {
            throw new DataScopeRewriteException("DEPT 数据范围没有任何部门");
        }
        String ids = context.deptIds().stream().map(DefaultPermissionRule::numeric).reduce((left, right) -> left + ", " + right).orElseThrow();
        return condition(qualifier + ".dept_id IN (" + ids + ")");
    }

    private static Expression condition(String sql) {
        try {
            return CCJSqlParserUtil.parseCondExpression(sql);
        } catch (Exception failure) {
            throw new DataScopeRewriteException("权限条件构造失败", failure);
        }
    }

    private static String numeric(Long value) {
        if (value == null) {
            throw new DataScopeRewriteException("权限上下文缺少 userId");
        }
        return Long.toString(value);
    }
}
