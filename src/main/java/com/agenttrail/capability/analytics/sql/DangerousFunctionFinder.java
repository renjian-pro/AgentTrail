package com.agenttrail.capability.analytics.sql;

import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 复用 JSqlParser 的完整递归访问，仅在函数节点增加安全检查。 */
final class DangerousFunctionFinder extends TablesNamesFinder<Void> {
    private static final Set<String> BLOCKED = Set.of(
            "load_file", "sleep", "benchmark", "system_user", "user", "database",
            "version", "current_user", "session_user", "master_pos_wait",
            "extractvalue", "updatexml");
    private final List<String> hits = new ArrayList<>();

    DangerousFunctionFinder() {
        init(true);
    }

    @Override
    public <S> Void visit(Function function, S context) {
        String name = function.getName() == null ? "" : function.getName().toLowerCase(Locale.ROOT);
        if (BLOCKED.contains(name)) {
            hits.add(name);
        }
        return super.visit(function, context);
    }

    List<String> hits() {
        return List.copyOf(hits);
    }
}
