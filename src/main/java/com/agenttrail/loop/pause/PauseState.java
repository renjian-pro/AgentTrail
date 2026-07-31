package com.agenttrail.loop.pause;

import com.agenttrail.loop.model.RunnableParams;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 一次暂停的完整快照——恢复时要能只凭这一个对象，把循环重新接续到暂停前的状态，
 * 不依赖任何还留在原进程内存里的东西（{@code Disposable}/{@code Sinks.Many} 除外，
 * 那两个进程内对象本来就不该出现在这里，暂停的意义就是让它们可以被安全丢弃）。
 *
 * @param conversationId    会话标识
 * @param messages          暂停那一刻的完整历史消息副本（含刚落进去的、带 tool_calls 的助手消息）——
 *                          是副本而不是引用，恢复时不会因为原 List 后续被复用/清空而跟着变
 * @param pendingToolCalls  被挂起、还没执行的工具调用；文本轮触发的暂停（理论上不会发生，
 *                          暂停只在有工具调用时触发）这里应为空列表，不是 null
 * @param reason            暂停原因，决定恢复时走哪条分支
 * @param safePoint         暂停发生时循环所处的阶段，恢复要从这个点接续
 * @param question          触发这一轮的原始用户提问，恢复时如果没有新指令要能继续引用它
 * @param params            原始运行时参数（会话 id、用户 id、系统级工具参数）——恢复执行挂起的
 *                          工具时，系统级参数注入必须用这个人的身份，不能凭空生成一个新的
 * @param pausedAtMillis    暂停发生的时刻，未来做 TTL 清理时用
 */
public record PauseState(
        String conversationId,
        List<Message> messages,
        List<PendingToolCall> pendingToolCalls,
        PauseReason reason,
        SafePoint safePoint,
        String question,
        RunnableParams params,
        long pausedAtMillis) {

    public PauseState {
        messages = List.copyOf(messages);
        pendingToolCalls = List.copyOf(pendingToolCalls);
    }
}
