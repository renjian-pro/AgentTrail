package com.agenttrail.loop.trace;

/**
 * 一轮模型调用的审计记录（issue #17）——一轮对应一条：本轮发给模型的完整上下文（{@link #inputData}）、
 * 本轮的产出（正文或工具调用参数，视 {@link #success} 与轮次性质而定）、思考过程、Token 消耗、耗时、
 * 成败与失败原因。
 *
 * <p>只在"模型调用"这个粒度记录，不单独记一条"工具执行结果"——工具结果会随下一轮的历史
 * 原样出现在下一条记录的 {@link #inputData} 里，重复记两遍没有额外信息量（对齐 agentx-core
 * {@code TraceManager}/{@code recordTrace} 的记法：它同样只在 finishRound 里调用一次）。
 *
 * @param conversationId  会话标识
 * @param round           轮次序号，从 1 开始
 * @param inputData       本轮发给模型的消息历史渲染文本
 * @param outputData      本轮产出：文本轮是最终正文，工具调用轮是重组后的工具调用列表渲染文本；
 *                        模型调用失败时为 null
 * @param think           本轮思考过程，没有时为 null
 * @param promptTokens    本轮 prompt token 消耗；模型未在响应里报告时为 -1
 * @param completionTokens 本轮 completion token 消耗；模型未在响应里报告时为 -1
 * @param durationMillis  本轮耗时（从发起这轮流式请求到流结束或报错）
 * @param success         本轮模型调用是否成功
 * @param errorMessage    失败时的错误信息；成功时为 null
 * @param recordedAtMillis 记录写入时刻
 * @param promptStamps    本轮用到的外置提示词标识（{@code id@version#hash}，多个用逗号分隔）；
 *                        没用到外置提示词时为 null。主对话轮次通常就是 null——系统提示词的组装
 *                        是条件拼接代码，按 requirements §6.2 明确豁免于提示词纳管，只有上下文压缩、
 *                        记忆提取这类走 {@code PromptRegistry} 的内部调用才会填上（issue #101）
 */
public record TraceRecord(
        String conversationId,
        int round,
        String inputData,
        String outputData,
        String think,
        long promptTokens,
        long completionTokens,
        long durationMillis,
        boolean success,
        String errorMessage,
        long recordedAtMillis,
        String promptStamps) {

    /** 兼容既有调用方：没有提示词标识时等价于旧构造。 */
    public TraceRecord(String conversationId, int round, String inputData, String outputData, String think,
            long promptTokens, long completionTokens, long durationMillis, boolean success,
            String errorMessage, long recordedAtMillis) {
        this(conversationId, round, inputData, outputData, think, promptTokens, completionTokens,
                durationMillis, success, errorMessage, recordedAtMillis, null);
    }
}
