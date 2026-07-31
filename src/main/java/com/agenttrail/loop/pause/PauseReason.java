package com.agenttrail.loop.pause;

/**
 * 暂停的原因，决定 {@link ResumeInstruction} 恢复时该走哪条分支。
 */
public enum PauseReason {

    /** 挂起的工具调用命中了"需要人工审批"名单，等审批结果——批准就执行，拒绝就不执行。 */
    HITL_APPROVAL,

    /** 用户主动中断，通常带着新指令——挂起的工具调用要被跳过，而不是照常执行或重试。 */
    USER_INTERRUPT
}
