package com.agenttrail.web;

import com.agenttrail.loop.deepresearch.DeepResearchReport;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DeepResearch 任务的进度追踪——纯内存，不像 {@code ppt_generation_task} 那样落库。
 *
 * <p>{@link com.agenttrail.loop.deepresearch.DeepResearchService#research} 本身是一次不带
 * checkpoint 的单体调用（需求澄清→主题生成→逐任务检索→综合报告全部在一次方法调用里跑完，
 * 中途没有落库任何中间状态），不像 PPT 状态机天然自带断点续传的地基。给它补一套完整的、
 * 可崩溃恢复的持久化任务表是比"让前端能轮询到进度"大得多的工作，这里先只解决轮询这一个
 * 问题——代价是应用重启会丢失所有进行中任务的记录，这一点和 PPT（重启后还能凭 taskId
 * 继续跑）不对等，是有意识的范围取舍，不是遗漏。
 */
final class DeepResearchTaskRegistry {

    private final AtomicLong taskIdSequence = new AtomicLong();
    private final Map<Long, DeepResearchTaskResponse> tasks = new ConcurrentHashMap<>();

    long start() {
        long taskId = taskIdSequence.incrementAndGet();
        tasks.put(taskId, DeepResearchTaskResponse.running(taskId));
        return taskId;
    }

    void complete(long taskId, DeepResearchReport report) {
        tasks.put(taskId, DeepResearchTaskResponse.success(taskId, report));
    }

    void fail(long taskId, String errorMsg) {
        tasks.put(taskId, DeepResearchTaskResponse.failed(taskId, errorMsg));
    }

    Optional<DeepResearchTaskResponse> find(long taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }
}
