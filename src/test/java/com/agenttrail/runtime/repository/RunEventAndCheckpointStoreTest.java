package com.agenttrail.runtime.repository;

import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.platform.ids.TaskId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 检查点与事件存储的行为契约——DeepResearch 是当前唯一的真实使用方
 * （{@code DeepResearchWorkflow} 写检查点、{@code DeepResearchTaskWorker} 按 sequence 重放事件）。
 *
 * <p>原来这两个断言和"任务队列可见性超时""outbox 重发""可持久化取消"混在同一个按工单编号命名的
 * 测试类里；那三样的被测代码在 Phase -1 已随自研工作流引擎一并删除，剩下的部分按被测对象重新命名。
 */
class RunEventAndCheckpointStoreTest {

    @Test
    void checkpointStoreKeepsEveryStepAndExposesTheLatest() {
        TaskId taskId = TaskId.newId();
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();

        checkpoints.save(taskId, "PLAN", "one");
        checkpoints.save(taskId, "SEARCH", "two");

        // latest 给恢复用，find(sequence) 给按步回看用——两者都要，不能只留一个
        assertEquals("two", checkpoints.latest(taskId).orElseThrow().stateSnapshot());
        assertEquals("one", checkpoints.find(taskId, 1).orElseThrow().stateSnapshot());
    }

    @Test
    void eventStoreReplaysStrictlyAfterTheGivenSequence() {
        TaskId taskId = TaskId.newId();
        RunId runId = RunId.newId();
        InMemoryRunEventStore events = new InMemoryRunEventStore();

        events.append(EventEnvelope.create(runId, taskId, null, "RunStarted", "test",
                EventEnvelope.Visibility.CLIENT, "{}"));
        events.append(EventEnvelope.create(runId, taskId, null, "ModelDelta", "test",
                EventEnvelope.Visibility.CLIENT, "{}"));

        // SSE 断线重连传的是"我已经收到的最后一个 sequence"，所以边界必须是开区间，
        // 否则客户端每次重连都会重复拿到最后一条
        assertEquals(2, events.afterSequence(runId, 0).size());
        assertEquals(1, events.afterSequence(runId, 1).size());
    }
}
