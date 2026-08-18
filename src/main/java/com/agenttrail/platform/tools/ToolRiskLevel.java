package com.agenttrail.platform.tools;

/** 跨循环、运行时和 HTTP 投影共享的工具风险等级，避免各层手写字符串产生漂移。 */
public enum ToolRiskLevel {
    READ_ONLY,
    HIGH_RISK
}
