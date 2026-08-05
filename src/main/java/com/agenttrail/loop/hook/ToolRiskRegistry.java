package com.agenttrail.loop.hook;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 工具名到治理风险等级的显式分类表；未知工具按只读处理。 */
public final class ToolRiskRegistry {

    private final Map<String, ToolRiskLevel> levels;

    public ToolRiskRegistry(Map<String, ToolRiskLevel> levels) {
        this.levels = Map.copyOf(levels);
    }

    public static ToolRiskRegistry defaults() {
        return new ToolRiskRegistry(Map.ofEntries(
                Map.entry("read_file", ToolRiskLevel.READ_ONLY),
                Map.entry("write_file", ToolRiskLevel.HIGH_RISK),
                Map.entry("edit_file", ToolRiskLevel.HIGH_RISK),
                Map.entry("list_files", ToolRiskLevel.READ_ONLY),
                Map.entry("glob_files", ToolRiskLevel.READ_ONLY),
                Map.entry("grep", ToolRiskLevel.READ_ONLY),
                Map.entry("bash", ToolRiskLevel.HIGH_RISK),
                Map.entry("load_file_content", ToolRiskLevel.READ_ONLY),
                Map.entry("list_tables", ToolRiskLevel.READ_ONLY),
                Map.entry("describe_tables", ToolRiskLevel.READ_ONLY),
                Map.entry("lookup_glossary", ToolRiskLevel.READ_ONLY),
                Map.entry("validate_sql", ToolRiskLevel.READ_ONLY),
                Map.entry("execute_sql", ToolRiskLevel.READ_ONLY),
                Map.entry("calculate", ToolRiskLevel.READ_ONLY),
                Map.entry("search_tools", ToolRiskLevel.READ_ONLY),
                Map.entry("TodoWrite", ToolRiskLevel.READ_ONLY),
                Map.entry("Skill", ToolRiskLevel.READ_ONLY)
        ));
    }

    public ToolRiskLevel riskOf(String toolName) {
        return levels.getOrDefault(toolName, ToolRiskLevel.READ_ONLY);
    }

    /** 返回指定风险等级的已注册工具名，供生产装配生成审批名单。 */
    public Set<String> toolsWithLevel(ToolRiskLevel level) {
        return levels.entrySet().stream()
                .filter(entry -> entry.getValue() == level)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
