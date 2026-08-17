package com.agenttrail.web.controller;

import com.agenttrail.capability.deepresearch.DeepResearchService;
import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker;
import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker.DeepResearchTaskStatus;
import com.agenttrail.capability.deepresearch.DeepResearchWorkflow;
import com.agenttrail.capability.deepresearch.InMemoryResearchArtifactStore;
import com.agenttrail.capability.deepresearch.InMemoryResearchTaskRecordStore;
import com.agenttrail.capability.deepresearch.ResearchTaskRecord;
import com.agenttrail.capability.deepresearch.ResearchTaskRecordStore;
import com.agenttrail.conversation.digest.ConversationDigestService;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.runtime.repository.InMemoryCheckpointStore;
import com.agenttrail.runtime.repository.InMemoryRunEventStore;
import com.agenttrail.web.dto.DeepResearchTaskResponse;
import com.agenttrail.web.service.CapabilityConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * issue #108 / R20：应用重启之后，查一个曾经存在的深度研究任务要拿到**诚实的终态**，不是 404。
 *
 * <h2>为什么 404 是错的</h2>
 *
 * 404 的语义是"这个任务从来不存在"，而事实是"它存在过，被重启打断了"——两者正好相反。用户
 * 看到 404 只会以为自己记错了 taskId，不会想到去重新发起。
 *
 * <p>这里模拟的就是重启后的形态：{@link ResearchTaskRecordStore} 里还留着记录（它落库了），
 * 而 controller 的 {@code handles} 是空的（{@code Future} 和 SSE 事件流没法持久化，进程一没就全丢）。
 * 两者的差集就是"被重启打断的任务"。
 *
 * <p><b>这一票明确不做续跑。</b>{@code DeepResearchService#research} 是一次不带 checkpoint 的单体
 * 调用，要做到 PPT 那种"凭 taskId 继续"得先把它拆成状态机，是另一个量级的工作。
 */
class DeepResearchRestartRecoveryTest {

    /** 重启后新起的 controller：records 里有历史记录，handles 是空的。 */
    private static DeepResearchController controllerAfterRestart(ResearchTaskRecordStore records) {
        DeepResearchTaskWorker worker = new DeepResearchTaskWorker(
                new DeepResearchWorkflow(mock(DeepResearchService.class), new InMemoryCheckpointStore(),
                        new InMemoryResearchArtifactStore(), new InMemoryRunEventStore()),
                new AgentTaskManager(), Runnable::run, new InMemoryRunEventStore());
        return new DeepResearchController(worker, mock(CapabilityConversationService.class),
                new ConversationDigestService(null) {
                    @Override
                    public String withContext(String conversationId, String userMessage, String consumer) {
                        return userMessage;
                    }
                }, records);
    }

    /**
     * <b>本票的核心验收。</b>没有 HTTP 上下文时 {@code currentUserId()} 返回 null，所以这条记录
     * 也建成 null 归属，走的是"是我的任务"那条路径。
     */
    @Test
    @DisplayName("重启后查一个进行中的任务，返回被打断的终态而不是 404")
    void reportsAnInterruptedTerminalStateInsteadOf404() {
        ResearchTaskRecordStore records = new InMemoryResearchTaskRecordStore();
        long taskId = records.create(null, "conv-1", "研究 Java 就业趋势");

        DeepResearchTaskResponse polled = controllerAfterRestart(records).status(taskId);

        assertThat(polled.status()).isEqualTo(DeepResearchTaskResponse.FAILED);
        assertThat(polled.errorMsg())
                .as("措辞要让用户看得懂发生了什么、以及该怎么办——「任务失败」会让他以为是研究本身出了问题")
                .isEqualTo(ResearchTaskRecord.INTERRUPTED_BY_RESTART);
        assertThat(polled.taskId()).isEqualTo(taskId);
    }

    /** 就地写回终态，下次再查不用重新判断一遍，也让 runningIdsFor 立刻不再返回它。 */
    @Test
    @DisplayName("这次查询顺手把记录落成终态，不会一直挂在 RUNNING")
    void persistsTheInterruptedStateSoItStopsLookingRunning() {
        ResearchTaskRecordStore records = new InMemoryResearchTaskRecordStore();
        long taskId = records.create(null, "conv-1", "问题");

        controllerAfterRestart(records).status(taskId);

        assertThat(records.find(taskId)).hasValueSatisfying(record ->
                assertThat(record.isRunning()).isFalse());
        assertThat(records.runningIdsFor(null)).isEmpty();
    }

    /** 已经跑完的任务重启后仍然查得到状态；报告正文在 {@code agent_session.timeline} 里，历史回放读那一份。 */
    @Test
    @DisplayName("重启后查一个已完成的任务，返回它真实的终态")
    void reportsTheRealTerminalStateOfTasksThatFinishedBeforeTheRestart() {
        ResearchTaskRecordStore records = new InMemoryResearchTaskRecordStore();
        long succeeded = records.create(null, "conv-1", "跑完的");
        long cancelled = records.create(null, "conv-1", "被取消的");
        records.markTerminal(succeeded, DeepResearchTaskStatus.SUCCESS, null);
        records.markTerminal(cancelled, DeepResearchTaskStatus.CANCELLED, null);
        DeepResearchController controller = controllerAfterRestart(records);

        assertThat(controller.status(succeeded).status()).isEqualTo(DeepResearchTaskResponse.SUCCESS);
        assertThat(controller.status(cancelled).status()).isEqualTo(DeepResearchTaskResponse.CANCELLED);
    }

    @Test
    @DisplayName("真的没有过这个 taskId 时才 404")
    void stillReturns404ForTaskIdsThatNeverExisted() {
        assertThatThrownBy(() -> controllerAfterRestart(new InMemoryResearchTaskRecordStore()).status(9_999L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * 别人的任务同样返回 404 而不是 403——403 会泄漏"这个 id 存在"，和 {@code checkOwner} 保持一致。
     */
    @Test
    @DisplayName("别人的任务返回 404，不泄漏它存在")
    void hidesTasksThatBelongToSomebodyElse() {
        ResearchTaskRecordStore records = new InMemoryResearchTaskRecordStore();
        long theirs = records.create("somebody-else", "conv-9", "别人的研究");

        assertThatThrownBy(() -> controllerAfterRestart(records).status(theirs))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
