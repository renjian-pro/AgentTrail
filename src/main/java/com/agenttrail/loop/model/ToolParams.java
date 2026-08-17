package com.agenttrail.loop.model;

import java.util.List;
import java.util.Map;

/**
 * {@link RunnableParams#toolParams()} 这条**模型不可见通道**的键名与读取口径，一处定义。
 *
 * <h2>为什么需要一个归属方</h2>
 *
 * 这个 map 是 {@code Map<String, Object>}，生产方（{@code ChatApplicationService}）和两个消费方
 * （{@code ChatToolScopeRuntimeAdapter}、{@code AgentLoopExecutor}）此前各写各的字面量、
 * 各自手搓强转，于是出现了**两个读取方对同一条通道有不同信念**的情况：
 *
 * <ul>
 *   <li>{@code flagEnabled} 容忍 {@code String}，因为暂停恢复会把 toolParams 过一遍
 *       {@code PauseStateJson}，{@code true} 可能变回 {@code "true"}
 *   <li>而 fileIds 的读取只认 {@code Number}——同一条恢复路径下，一个 {@code ["12"]} 会被静默丢掉，
 *       表现为"恢复之后这一轮的附件不见了"，且没有任何报错
 * </ul>
 *
 * 键名和强转放在一起，才不会出现"改了生产方忘了改消费方""一个宽容一个严格"这类只在恢复路径上
 * 才复现的问题。
 */
public final class ToolParams {

    private ToolParams() {
    }

    /** 当前用户。走不可见通道是因为让模型自己填 userId 等于把越权口子交给可被诱导的组件。 */
    public static final String USER_ID = "userId";
    public static final String CONVERSATION_ID = "conversation_id";
    /** 这次对话挂不挂联网搜索工具（issue #22）。 */
    public static final String WEB_SEARCH_ENABLED = "webSearchEnabled";
    /** 命中时整个执行器切到 {@code forAnalytics}（issue #106）。 */
    public static final String ANALYTICS_ENABLED = "analyticsEnabled";
    /** 这一轮显式附带的文件（issue #110 / R21）。模型绝不能控制自己能看到哪些文件。 */
    public static final String FILE_IDS = "fileIds";

    /**
     * 读一个布尔开关。
     *
     * <p>**同时接受 {@code Boolean} 和字符串 {@code "true"}**：暂停恢复时 toolParams 是从
     * {@code PauseStateJson} 反序列化回来的，布尔可能变成字符串。少了这份宽容，被中断的分析会话
     * 恢复后会拿到普通聊天执行器——那正是 issue #96 修过的故障。
     */
    public static boolean flag(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    /**
     * 读一列 id。
     *
     * <p>和 {@link #flag} 同样的宽容理由：JSON 往返之后数字可能回来成 {@code Integer}、
     * {@code Long} 甚至字符串，任何一种都该认。解析不了的单个元素跳过而不是整列作废——
     * 键不存在（老调用方、内部编排子调用）就是"这一轮没带"，不是错误。
     */
    public static List<Long> longList(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(ToolParams::toLongOrNull).filter(java.util.Objects::nonNull).toList();
    }

    private static Long toLongOrNull(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
