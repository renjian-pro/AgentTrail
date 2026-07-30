package com.agenttrail.loop.core;

/**
 * 一轮响应的性质分类，只有等该轮的流完整到达之后才能确定。
 */
enum RoundMode {

    /** 整轮没有出现任何工具调用——这一轮的文本就是最终答案，循环结束。 */
    TEXT,

    /** 本轮出现了工具调用——执行工具、把结果拼回消息历史，然后进入下一轮。 */
    TOOL_CALL
}
