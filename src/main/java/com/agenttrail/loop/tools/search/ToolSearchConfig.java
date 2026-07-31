package com.agenttrail.loop.tools.search;

/**
 * 延迟工具检索配置。挂了它才启用 ToolSearch；不挂时行为跟没有这个机制完全一样。
 *
 * @param mode       检索方式
 * @param maxResults 单次检索最多返回的工具数
 */
public record ToolSearchConfig(Mode mode, int maxResults) {

    private static final int DEFAULT_MAX_RESULTS = 5;

    public ToolSearchConfig {
        if (mode == null) {
            mode = Mode.HYBRID;
        }
        if (maxResults <= 0) {
            maxResults = DEFAULT_MAX_RESULTS;
        }
    }

    public static ToolSearchConfig defaults() {
        return new ToolSearchConfig(Mode.HYBRID, DEFAULT_MAX_RESULTS);
    }

    /** 关键词打分最快；LLM 更准但多一次调用；混合先关键词，没结果再兜底 LLM。 */
    public enum Mode {
        KEYWORD, LLM, HYBRID
    }
}
