package com.agenttrail.capability.analytics.permission;

import com.agenttrail.sys.datascope.DataScopeContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;

import java.util.Set;

public interface PermissionRule {
    Set<String> managedTables();
    Expression buildCondition(Table table, DataScopeContext context);
}
