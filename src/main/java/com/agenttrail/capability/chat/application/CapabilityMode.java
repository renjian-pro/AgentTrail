package com.agenttrail.capability.chat.application;

/**
 * 前端选中的能力模式（issue #106 / R13a）。四者互斥，同一时刻只能选一个，
 * 会话内可逐轮切换（`docs/requirements.md` §7.2）。
 *
 * <p><b>为什么必须是枚举而不是 String</b>：此前是一句
 * {@code boolean analyticsEnabled = "analytics".equals(mode)}，于是 {@code "analytics"} 之外的
 * <b>任何</b>取值——拼错的 {@code "Analytics"}、前端路由错发过来的 {@code "ppt"}、多打一个空格——
 * 都会静默降级按普通聊天跑完，接口返回 200、日志干净。取值只有一个的时候这只是个隐患；
 * 扩到四个之后，"前端传错一个字符 → 模型没有数据库工具 → 开始编数据画图"就成了一条
 * 完全查不出来的线上故障链。
 *
 * <p><b>{@link #CHAT} 是显式取值，不是 null</b>：{@code null} 表示"前端没传"（合法，落 CHAT），
 * 未注册的字符串表示"前端传错"（400）。两者必须能区分开，否则 400 判定无从下手——
 * 这正是 {@link #parse} 和 {@link #require} 分成两个方法的原因。
 */
public enum CapabilityMode {
    /** 普通对话。Runtime 基线执行器。 */
    CHAT("chat"),
    /** 数据分析。切 {@code forAnalytics} 执行器：工具集不相交、20 轮、连续失败 3 次止损。 */
    ANALYTICS("analytics"),
    /**
     * 深度研究。**不走 {@code /agent/v1/chat}**——它有自己的异步任务链路
     * （{@code /agent/v1/deepresearch}：create + SSE + cancel）。在 chat 端点上出现即前端路由错了。
     */
    RESEARCH("research"),
    /**
     * PPT 生成。同样**不走 {@code /agent/v1/chat}**——{@code /agent/v1/ppt/*}，
     * 且带断点续跑和产物下载，SSE 单次流承载不了。
     */
    PPT("ppt");

    /** 前端传上来的字面量。大小写敏感：容忍大小写等于容忍前端拼错，而拼错正是要暴露的东西。 */
    private final String wireValue;

    CapabilityMode(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * 这个模式能不能由 {@code /agent/v1/chat} 承载。
     *
     * <p>{@link #RESEARCH}/{@link #PPT} 返回 false：它们是异步任务协议，不是 SSE 单次流。
     * 静默把它们当普通聊天跑会把"前端路由错了"这个事实藏起来——用户会拿到一段闲聊回复，
     * 而不是一份报告，且没有任何地方能看出发生了什么。
     */
    public boolean servedByChatEndpoint() {
        return this == CHAT || this == ANALYTICS;
    }

    /**
     * 解析前端传来的字面量。
     *
     * @param raw {@code null} 或空白表示"没传"，落 {@link #CHAT}
     * @return 解析结果；{@code null} 表示<b>传了但不认识</b>——调用方据此返回 400，不要当成 CHAT
     */
    public static CapabilityMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CHAT;
        }
        for (CapabilityMode mode : values()) {
            if (mode.wireValue.equals(raw)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * 解析并要求这个模式能由 chat 端点承载，否则抛 {@link IllegalArgumentException}
     * （由 {@code AgentLoopController} 转成 400）。
     *
     * <p>异常消息刻意带上收到的原值和可接受的取值——这条错误的读者是正在接前端的人，
     * 只说"invalid mode"等于让他自己去翻枚举定义。
     */
    public static CapabilityMode require(String raw) {
        CapabilityMode mode = parse(raw);
        if (mode == null) {
            throw new IllegalArgumentException(
                    "未知的能力模式: " + raw + "，可接受的取值: chat / analytics（research / ppt 走各自的任务接口）");
        }
        if (!mode.servedByChatEndpoint()) {
            throw new IllegalArgumentException(
                    "能力模式 " + raw + " 不由 /agent/v1/chat 承载，请调用它自己的任务接口："
                            + (mode == RESEARCH ? "/agent/v1/deepresearch" : "/agent/v1/ppt/create"));
        }
        return mode;
    }
}
