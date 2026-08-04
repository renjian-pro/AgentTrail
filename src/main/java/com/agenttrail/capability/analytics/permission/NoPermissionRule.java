package com.agenttrail.capability.analytics.permission;

import com.agenttrail.sys.datascope.DataScopeContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;

import java.util.Set;

/** 明确声明无需数据范围条件的主数据表。 */
public final class NoPermissionRule implements PermissionRule {
    private final Set<String> tables;

    public NoPermissionRule(Set<String> tables) {
        this.tables = Set.copyOf(tables);
    }

    @Override
    public Set<String> managedTables() {
        return tables;
    }

    @Override
    public Expression buildCondition(Table table, DataScopeContext context) {
        return null;
    }
}
