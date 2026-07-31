package com.agenttrail.loop.stageoutput;

import com.agenttrail.loop.model.RunnableParams;

/**
 * 传给 {@link StageOutputProvider#produce} 的只读快照。
 *
 * @param question 原始用户提问
 * @param answer   最终答案；只有 {@link StageTiming#BEFORE_COMPLETE} 时才有值，
 *                 其它两个钩子点答案还没确定，传 null
 * @param params   本次调用的运行时参数
 */
public record StageContext(String question, String answer, RunnableParams params) {
}
