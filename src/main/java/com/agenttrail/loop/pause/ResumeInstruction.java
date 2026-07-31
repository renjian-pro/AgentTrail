package com.agenttrail.loop.pause;

/**
 * 恢复一次暂停时，调用方要说清楚"按哪种方式恢复"——两种恢复方式对挂起的工具调用做的事
 * 完全相反（执行 vs 跳过），不能用一个笼统的"继续"了事。
 */
public sealed interface ResumeInstruction {

    /**
     * HITL 审批结果。批准就真正执行挂起的工具调用；拒绝就不执行，把拒绝原因当工具结果
     * 喂回模型，让模型看到"这条路走不通"、自己决定要不要换个方式。
     */
    record ApprovalDecision(boolean approved, String rejectionReason) implements ResumeInstruction {

        public static ApprovalDecision approve() {
            return new ApprovalDecision(true, null);
        }

        public static ApprovalDecision reject(String reason) {
            return new ApprovalDecision(false, reason);
        }
    }

    /**
     * 用户中断后带的新指令。挂起的工具调用被跳过（不执行、不重试），新消息直接注入继续——
     * 用户既然改主意了，继续执行一个他已经不关心的旧调用没有意义。
     */
    record NewInstruction(String message) implements ResumeInstruction {
    }
}
