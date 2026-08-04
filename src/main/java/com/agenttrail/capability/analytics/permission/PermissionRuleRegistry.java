package com.agenttrail.capability.analytics.permission;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class PermissionRuleRegistry {
    private final Map<String, PermissionRule> rules = new LinkedHashMap<>();

    public PermissionRuleRegistry(PermissionRule... registered) {
        for (PermissionRule rule : registered) {
            for (String table : rule.managedTables()) {
                rules.put(table.toLowerCase(Locale.ROOT), rule);
            }
        }
    }

    public PermissionRule ruleFor(String tableName) {
        PermissionRule rule = rules.get(tableName == null ? "" : tableName.toLowerCase(Locale.ROOT));
        if (rule == null) {
            throw new DataScopeRewriteException("数据权限未登记表：" + tableName);
        }
        return rule;
    }

    public boolean contains(String tableName) {
        return rules.containsKey(tableName == null ? "" : tableName.toLowerCase(Locale.ROOT));
    }
}
