package com.agenttrail.capability.deepresearch;

import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker.DeepResearchTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #108 / R20：任务元信息存储的行为契约。跑内存实现，JDBC 实现的等价断言在
 * {@link JdbcResearchTaskRecordStoreIT}（要真实 MySQL）。
 */
class ResearchTaskRecordStoreContractTest {

    private ResearchTaskRecordStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryResearchTaskRecordStore();
    }

    @Test
    @DisplayName("新建的任务是 RUNNING，taskId 单调递增不复用")
    void createsRunningTasksWithMonotonicIds() {
        long first = store.create("u-1", "conv-1", "第一个问题");
        long second = store.create("u-1", "conv-1", "第二个问题");

        assertThat(second).isGreaterThan(first);
        assertThat(store.find(first)).hasValueSatisfying(record -> {
            assertThat(record.isRunning()).isTrue();
            assertThat(record.question()).isEqualTo("第一个问题");
            assertThat(record.userId()).isEqualTo("u-1");
        });
    }

    /**
     * **这条是 R20 的核心。** 进程没了，那些 RUNNING 不可能再自己推进——留着它们，前端会一直
     * 轮询一个永远不会变的状态，比 404 更难判断。启动扫描给一个诚实的终态。
     */
    @Test
    @DisplayName("启动扫描把残留的 RUNNING 标成「被重启打断」，不碰已经是终态的")
    void marksLeftoverRunningTasksAsInterruptedOnRestart() {
        long running = store.create("u-1", "conv-1", "还在跑");
        long done = store.create("u-1", "conv-1", "已经跑完");
        store.markTerminal(done, DeepResearchTaskStatus.SUCCESS, null);

        assertThat(store.markRunningAsInterrupted()).isEqualTo(1);

        assertThat(store.find(running)).hasValueSatisfying(record -> {
            assertThat(record.status()).isEqualTo(DeepResearchTaskStatus.FAILED);
            assertThat(record.errorMsg()).isEqualTo(ResearchTaskRecord.INTERRUPTED_BY_RESTART);
        });
        assertThat(store.find(done)).hasValueSatisfying(record ->
                assertThat(record.status()).as("已经跑完的不该被扫描改写").isEqualTo(DeepResearchTaskStatus.SUCCESS));
    }

    /**
     * worker 的完成事件和用户点取消可能几乎同时到达。**先落地的那个才是真实原因**——
     * 没有这道保护，一个已经 SUCCESS 的任务会被随后到达的取消请求改写成 CANCELLED，
     * 用户手里明明有报告，状态却说他取消了。
     */
    @Test
    @DisplayName("终态只写一次，后到的不覆盖先到的")
    void keepsTheFirstTerminalStatusItWasGiven() {
        long id = store.create("u-1", "conv-1", "问题");

        store.markTerminal(id, DeepResearchTaskStatus.SUCCESS, null);
        store.markTerminal(id, DeepResearchTaskStatus.CANCELLED, null);

        assertThat(store.find(id)).hasValueSatisfying(record ->
                assertThat(record.status()).isEqualTo(DeepResearchTaskStatus.SUCCESS));
    }

    @Test
    @DisplayName("进行中列表按用户隔离，终态的不在其中")
    void listsOnlyTheRequestingUsersRunningTasks() {
        long mine = store.create("u-1", "conv-1", "我的");
        long mineDone = store.create("u-1", "conv-1", "我的已完成");
        store.create("u-2", "conv-2", "别人的");
        store.markTerminal(mineDone, DeepResearchTaskStatus.SUCCESS, null);

        assertThat(store.runningIdsFor("u-1")).containsExactly(mine);
        assertThat(store.runningIdsFor(null)).isEmpty();
    }

    @Test
    @DisplayName("查不到的 id 返回空，不抛异常——调用方据此决定是不是真的 404")
    void returnsEmptyForUnknownIds() {
        assertThat(store.find(9_999L)).isEmpty();
    }
}
