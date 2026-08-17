package com.agenttrail.capability.deepresearch;

import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker.DeepResearchTaskStatus;

import java.util.List;
import java.util.Optional;

/**
 * 深度研究任务元信息的存取（issue #108 / R20）。参照 {@code FileStore}/{@code PptTaskStore}
 * 的既有模式：接口不假设存储介质，生产装配走 JDBC，测试用内存实现。
 *
 * <p>报告正文不经过这里——见 {@link ResearchTaskRecord} 的说明。
 */
public interface ResearchTaskRecordStore {

    /** 建一条 RUNNING 记录，返回自增主键（即对外的 taskId）。 */
    long create(String userId, String conversationId, String question);

    /** 推进到终态并记录原因；已经是终态时不覆盖——先到的那个才是真实原因。 */
    void markTerminal(long id, DeepResearchTaskStatus status, String errorMsg);

    Optional<ResearchTaskRecord> find(long id);

    /** 某个用户当前进行中的任务，按 id 正序。{@code userId} 为 null 时返回空列表。 */
    List<Long> runningIdsFor(String userId);

    /**
     * 把所有还挂着 RUNNING 的记录标成被重启打断，返回受影响的条数。
     *
     * <p><b>在应用启动时调用一次。</b>进程已经没了，那些任务不可能再自己推进——留着 RUNNING
     * 会让前端一直轮询一个永远不会变的状态。单实例部署下这个扫描是安全的；将来真要多实例，
     * 得先有实例标识或租约才能判断"这条 RUNNING 是不是我的"，那是另一个话题。
     *
     * <p>这里是 {@link ResearchTaskRecord#INTERRUPTED_BY_RESTART} 的**唯一写入点**，
     * 调用方（启动扫描、以及查询时发现漏网的那条路径）都从这里过，不各自拼终态。
     */
    int markRunningAsInterrupted();

    /**
     * 单条的"发现它还挂着 RUNNING 就标成被打断"，供查询路径在启动扫描没覆盖到时就地补一刀
     * （扫描没跑、或刚好并发）。已经是终态的原样返回。
     */
    default Optional<ResearchTaskRecord> resolveStale(long id) {
        return find(id).map(record -> {
            if (!record.isRunning()) {
                return record;
            }
            markTerminal(id, DeepResearchTaskStatus.FAILED, ResearchTaskRecord.INTERRUPTED_BY_RESTART);
            return find(id).orElse(record);
        });
    }
}
