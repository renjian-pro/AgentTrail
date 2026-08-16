package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.pause.InMemoryPauseStateStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.pause.PauseReason;
import com.agenttrail.loop.pause.PauseState;
import com.agenttrail.loop.pause.PendingToolCall;
import com.agenttrail.loop.pause.ResumeInstruction;
import com.agenttrail.loop.pause.SafePoint;
import com.agenttrail.loop.task.AgentTaskManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * issue #13：暂停必须能拍下"消息副本 + 挂起的工具调用 + 恢复点 + 暂停原因"这份完整快照，
 * 且能按两条不同的路径恢复——HITL 审批通过/拒绝，和用户带新指令中断。两条路径对挂起工具
 * 调用的处理完全相反（执行 vs 跳过），所以分开建测试而不是共用一套断言。
 */
class AgentLoopExecutorPauseResumeTest {

    private static final String APPROVAL_REQUIRED_TOOL = "chargeCard";

    @Test
    void pausesInsteadOfExecutingAnApprovalRequiredToolAndSnapshotsEverythingNeeded() {
        InMemoryPauseStateStore store = new InMemoryPauseStateStore();
        RecordingToolCallback chargeTool = new RecordingToolCallback(APPROVAL_REQUIRED_TOOL, "charges a card", "charged");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", APPROVAL_REQUIRED_TOOL, "{\"amount\":100}"))
        );
        AgentLoopExecutor executor = executorWith(chatModel, store, chargeTool);

        List<AgentStreamEvent> events = executor
                .stream("给我充值 100 元", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        // 工具从未被执行——审批之前不能有任何副作用
        assertThat(chargeTool.recordedArguments()).isEmpty();
        assertThat(events).contains(new AgentStreamEvent.Paused("conv-1", PauseReason.HITL_APPROVAL));

        PauseState snapshot = store.find("conv-1").orElseThrow();
        assertThat(snapshot.reason()).isEqualTo(PauseReason.HITL_APPROVAL);
        assertThat(snapshot.safePoint()).isEqualTo(SafePoint.BEFORE_TOOL_EXECUTION);
        assertThat(snapshot.pendingToolCalls())
                .containsExactly(new PendingToolCall("call-1", APPROVAL_REQUIRED_TOOL, "{\"amount\":100}"));
        assertThat(snapshot.messages()).extracting(Message::getText).contains("给我充值 100 元");
        assertThat(snapshot.question()).isEqualTo("给我充值 100 元");
    }

    /** 没有命中审批名单的工具照常执行，不该被误伤成暂停。 */
    @Test
    void doesNotPauseForToolsOutsideTheApprovalList() {
        InMemoryPauseStateStore store = new InMemoryPauseStateStore();
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "echoes", "pong");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "echo", "{}")),
                List.of(text("done"))
        );
        AgentLoopExecutor executor = executorWith(chatModel, store, echoTool);

        List<AgentStreamEvent> events = executor.stream("echo", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(echoTool.recordedArguments()).containsExactly("{}");
        assertThat(events).noneMatch(AgentStreamEvent.Paused.class::isInstance);
        assertThat(store.find("conv-1")).isEmpty();
    }

    // ==================== 恢复：HITL 审批 ====================

    @Test
    void approvingAPausedToolActuallyExecutesItAndContinuesTheLoop() {
        InMemoryPauseStateStore store = new InMemoryPauseStateStore();
        RecordingToolCallback chargeTool = new RecordingToolCallback(APPROVAL_REQUIRED_TOOL, "charges a card", "charged");
        ScriptedChatModel pauseModel = new ScriptedChatModel(
                List.of(toolCall("call-1", APPROVAL_REQUIRED_TOOL, "{\"amount\":100}")));
        AgentLoopExecutor pausingExecutor = executorWith(pauseModel, store, chargeTool);
        pausingExecutor.stream("给我充值 100 元", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        ScriptedChatModel resumeModel = new ScriptedChatModel(List.of(text("充值成功")));
        AgentLoopExecutor resumingExecutor = executorWith(resumeModel, store, chargeTool);

        List<AgentStreamEvent> events = resumingExecutor
                .resume("conv-1", ResumeInstruction.ApprovalDecision.approve())
                .collectList().block(Duration.ofSeconds(5));

        assertThat(chargeTool.recordedArguments()).containsExactly("{\"amount\":100}");
        assertThat(events).contains(new AgentStreamEvent.Text("充值成功"));
        // 恢复消费掉了快照，同一个会话不能被重复恢复
        assertThat(store.find("conv-1")).isEmpty();
    }

    @Test
    void rejectingAPausedToolNeverExecutesItButStillContinuesTheLoopWithAnErrorResult() {
        InMemoryPauseStateStore store = new InMemoryPauseStateStore();
        RecordingToolCallback chargeTool = new RecordingToolCallback(APPROVAL_REQUIRED_TOOL, "charges a card", "charged");
        ScriptedChatModel pauseModel = new ScriptedChatModel(
                List.of(toolCall("call-1", APPROVAL_REQUIRED_TOOL, "{\"amount\":100}")));
        AgentLoopExecutor pausingExecutor = executorWith(pauseModel, store, chargeTool);
        pausingExecutor.stream("给我充值 100 元", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        ScriptedChatModel resumeModel = new ScriptedChatModel(List.of(text("好的，已取消")));
        AgentLoopExecutor resumingExecutor = executorWith(resumeModel, store, chargeTool);

        resumingExecutor.resume("conv-1", ResumeInstruction.ApprovalDecision.reject("金额异常"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(chargeTool.recordedArguments()).as("拒绝的调用绝不能被执行").isEmpty();
        assertThat(resumeModel.messagesAtRound(0))
                .filteredOn(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatExtracting(ToolResponseMessage::getResponses)
                .extracting(ToolResponseMessage.ToolResponse::responseData)
                .anyMatch(data -> data != null && data.contains("金额异常"));
    }

    // ==================== 恢复：用户中断 + 新指令 ====================

    @Test
    void resumingWithANewInstructionSkipsThePendingToolAndInjectsTheNewMessage() {
        InMemoryPauseStateStore store = new InMemoryPauseStateStore();
        RecordingToolCallback chargeTool = new RecordingToolCallback(APPROVAL_REQUIRED_TOOL, "charges a card", "charged");
        ScriptedChatModel pauseModel = new ScriptedChatModel(
                List.of(toolCall("call-1", APPROVAL_REQUIRED_TOOL, "{\"amount\":100}")));
        AgentLoopExecutor pausingExecutor = executorWith(pauseModel, store, chargeTool);
        pausingExecutor.stream("给我充值 100 元", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        ScriptedChatModel resumeModel = new ScriptedChatModel(List.of(text("好的，改充 50 元")));
        AgentLoopExecutor resumingExecutor = executorWith(resumeModel, store, chargeTool);

        resumingExecutor.resume("conv-1", new ResumeInstruction.NewInstruction("算了，充 50 元就好"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(chargeTool.recordedArguments()).as("被跳过的调用绝不能被执行").isEmpty();
        assertThat(resumeModel.messagesAtRound(0)).extracting(Message::getText)
                .anyMatch(text -> text != null && text.contains("算了，充 50 元就好"));
    }

    /**
     * 恢复必须接着暂停时的轮次继续数，而不是从 0 重开一份全新的 maxRounds 预算——
     * 否则反复暂停/恢复能绕开轮次上限，变相无限轮下去。maxRounds=1 时，第 1 轮触发暂停已经
     * 用掉了唯一的额度，resume 之后调度的那一轮必须已经超过预算、模型看不到任何工具。
     */
    @Test
    void resumeContinuesTheRoundBudgetInsteadOfGrantingAFreshOne() {
        InMemoryPauseStateStore store = new InMemoryPauseStateStore();
        RecordingToolCallback chargeTool = new RecordingToolCallback(APPROVAL_REQUIRED_TOOL, "charges a card", "charged");
        ScriptedChatModel pauseModel = new ScriptedChatModel(
                List.of(toolCall("call-1", APPROVAL_REQUIRED_TOOL, "{\"amount\":100}")));
        AgentLoopExecutor pausingExecutor = executorWith(pauseModel, store, chargeTool, 1);
        pausingExecutor.stream("给我充值 100 元", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        ScriptedChatModel resumeModel = new ScriptedChatModel(List.of(text("充值成功")));
        AgentLoopExecutor resumingExecutor = executorWith(resumeModel, store, chargeTool, 1);

        resumingExecutor.resume("conv-1", ResumeInstruction.ApprovalDecision.approve())
                .collectList().block(Duration.ofSeconds(5));

        // 挂起的调用照样要执行——轮次预算耗尽只影响"模型这一轮还能不能再发起新的工具调用"
        assertThat(chargeTool.recordedArguments()).containsExactly("{\"amount\":100}");
        assertThat(resumeModel.toolNamesAtRound(0))
                .as("暂停已经用掉了 maxRounds=1 的唯一额度，resume 后这一轮必须已经超预算、不挂任何工具")
                .isEmpty();
    }

    // ==================== 边界 ====================

    @Test
    void resumingWithoutAPauseConfigThrows() {
        AgentLoopExecutor executor = new AgentLoopExecutor(
                new ScriptedChatModel(List.of(text("done"))), List.of(), 5);

        assertThatThrownBy(() -> executor.resume("conv-1", ResumeInstruction.ApprovalDecision.approve()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resumingAConversationWithNoPauseSnapshotThrows() {
        InMemoryPauseStateStore store = new InMemoryPauseStateStore();
        AgentLoopExecutor executor = executorWith(new ScriptedChatModel(List.of(text("done"))), store,
                new RecordingToolCallback(APPROVAL_REQUIRED_TOOL, "charges a card", "charged"));

        assertThatThrownBy(() -> executor.resume("never-paused", ResumeInstruction.ApprovalDecision.approve()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AgentLoopExecutor executorWith(ScriptedChatModel chatModel, InMemoryPauseStateStore store,
                                                  RecordingToolCallback tool) {
        return executorWith(chatModel, store, tool, 5);
    }

    private static AgentLoopExecutor executorWith(ScriptedChatModel chatModel, InMemoryPauseStateStore store,
                                                  RecordingToolCallback tool, int maxRounds) {
        PauseConfig pauseConfig = new PauseConfig(Set.of(APPROVAL_REQUIRED_TOOL), store);
        return AgentLoopExecutor.builder(chatModel, List.of(tool), maxRounds)
                .pauseConfig(pauseConfig)
                .build();
    }
}
