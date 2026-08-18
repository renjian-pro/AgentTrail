package com.agenttrail.platform.tools;

/** 标识恢复时应从工具执行前还是工具阶段处理后继续。 */
public enum ResumeSafePoint {
    BEFORE_TOOL_EXECUTION,
    AFTER_TOOL_EXECUTION
}
