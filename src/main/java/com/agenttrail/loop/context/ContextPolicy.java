package com.agenttrail.loop.context;

import java.util.HashSet;
import java.util.Set;

/**
 * 上下文压缩策略。不配置则完全不压缩，循环行为与没有这个机制时一致。
 *
 * @param tokenThreshold          估算 token 超过这个数就触发整体摘要压缩
 * @param keepRecentTools         最近 N 组工具调用/结果保留原文，更早的才压
 * @param maxToolLength           单条工具内容超过这个字符数才压；设为 0 表示不做长度压缩
 * @param protectedTools          这些工具的内容永不压缩（见 {@link #BUILTIN_PROTECTED_TOOLS}）
 * @param retainLatestOnlyMarkers 消息文本以这些标记开头的，只保留最新一条，更早的整条丢弃
 *                                （issue #37）——不是"压缩内容"，是"结构性删除"：旧的一条一旦
 *                                被新的一条取代就没有参考价值，不像工具结果那样有"占位符"这种
 *                                半保留状态可用。典型用法：DeepResearch 用它标记批判反馈消息，
 *                                只让最新一轮的批判意见参与后续渲染/压缩，更早几轮的不再累积。
 *                                默认空集合，不启用这条规则
 */
public record ContextPolicy(
        int tokenThreshold,
        int keepRecentTools,
        int maxToolLength,
        Set<String> protectedTools,
        Set<String> retainLatestOnlyMarkers) {

    public static final int DEFAULT_TOKEN_THRESHOLD = 60_000;
    public static final int DEFAULT_KEEP_RECENT_TOOLS = 4;
    public static final int DEFAULT_MAX_TOOL_LENGTH = 200;

    /**
     * 内置永不压缩的工具。
     *
     * <p>这两类工具的输出是**持续有效的指令**，不是一次性的查询结果：技能内容规定了后续该怎么做，
     * 待办清单是模型判断"还剩什么没干"的依据。压掉它们等于让模型中途失忆——
     * 会看到自己领过一个任务，却不知道任务内容是什么。
     */
    private static final Set<String> BUILTIN_PROTECTED_TOOLS = Set.of("Skill", "TodoWrite");

    public ContextPolicy {
        Set<String> merged = new HashSet<>(BUILTIN_PROTECTED_TOOLS);
        if (protectedTools != null) {
            merged.addAll(protectedTools);
        }
        protectedTools = Set.copyOf(merged);
        retainLatestOnlyMarkers = (retainLatestOnlyMarkers == null) ? Set.of() : Set.copyOf(retainLatestOnlyMarkers);
    }

    public static ContextPolicy defaults() {
        return new ContextPolicy(
                DEFAULT_TOKEN_THRESHOLD, DEFAULT_KEEP_RECENT_TOOLS, DEFAULT_MAX_TOOL_LENGTH, Set.of(), Set.of());
    }

    public boolean isProtected(String toolName) {
        return toolName != null && protectedTools.contains(toolName);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private int tokenThreshold = DEFAULT_TOKEN_THRESHOLD;
        private int keepRecentTools = DEFAULT_KEEP_RECENT_TOOLS;
        private int maxToolLength = DEFAULT_MAX_TOOL_LENGTH;
        private Set<String> protectedTools = Set.of();
        private Set<String> retainLatestOnlyMarkers = Set.of();

        public Builder tokenThreshold(int tokenThreshold) {
            this.tokenThreshold = tokenThreshold;
            return this;
        }

        public Builder keepRecentTools(int keepRecentTools) {
            this.keepRecentTools = keepRecentTools;
            return this;
        }

        public Builder maxToolLength(int maxToolLength) {
            this.maxToolLength = maxToolLength;
            return this;
        }

        public Builder protectedTools(String... tools) {
            this.protectedTools = Set.of(tools);
            return this;
        }

        public Builder retainLatestOnlyMarkers(String... markers) {
            this.retainLatestOnlyMarkers = Set.of(markers);
            return this;
        }

        public ContextPolicy build() {
            return new ContextPolicy(
                    tokenThreshold, keepRecentTools, maxToolLength, protectedTools, retainLatestOnlyMarkers);
        }
    }
}
