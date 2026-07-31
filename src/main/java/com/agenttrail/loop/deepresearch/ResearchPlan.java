package com.agenttrail.loop.deepresearch;

import java.util.List;

/**
 * 结构化输出的目标类型（issue #18 机制复用）——包一层而不是直接对 {@code List<ResearchTask>}
 * declar 结构化输出，因为 {@link com.agenttrail.loop.model.OutputType} 目前只支持单对象类型
 * （见其类注释），没有必要为这一个调用方去扩展共享机制。
 */
public record ResearchPlan(List<ResearchTask> tasks) {
}
