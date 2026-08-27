package com.agenttrail.capability.ppt.application;

/**
 * PPT 任务创建前的需求预检接口。调用方只需提供会话上下文，不需要知道字段提取、默认值确认和追问规则。
 */
public interface PptRequirementPreflight {
    PptPreflightOutcome assess(String conversationId, String conversationContext);
}
