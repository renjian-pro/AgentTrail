package com.agenttrail.web;

import com.agenttrail.loop.deepresearch.DeepResearchReport;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
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
    private final Map<Long, TaskHandle> handles = new ConcurrentHashMap<>();

    long start(String userId) {
        long taskId = taskIdSequence.incrementAndGet();
        tasks.put(taskId, DeepResearchTaskResponse.running(taskId));
        handles.put(taskId, new TaskHandle(userId, null));
        return taskId;
    }

    void attachFuture(long taskId, Future<?> future) {
        handles.computeIfPresent(taskId, (ignored, existing) -> new TaskHandle(existing.userId(), future));
    }

    void complete(long taskId, DeepResearchReport report) {
        tasks.compute(taskId, (ignored, existing) -> existing != null && DeepResearchTaskResponse.CANCELLED.equals(existing.status())
                ? existing : DeepResearchTaskResponse.success(taskId, report));
    }

    void fail(long taskId, String errorMsg) {
        tasks.compute(taskId, (ignored, existing) -> existing != null && DeepResearchTaskResponse.CANCELLED.equals(existing.status())
                ? existing : DeepResearchTaskResponse.failed(taskId, errorMsg));
    }

    boolean belongsTo(long taskId, String userId) {
        TaskHandle handle = handles.get(taskId);
        return handle != null && Objects.equals(handle.userId(), userId);
    }

    /** @return true 表示成功发起取消请求；找不到任务或任务已结束返回 false */
    boolean cancel(long taskId) {
        TaskHandle handle = handles.get(taskId);
        if (handle == null || handle.future() == null || handle.future().isDone()) {
            return false;
        }
        boolean cancelled = handle.future().cancel(true);
        if (cancelled) {
            tasks.put(taskId, DeepResearchTaskResponse.cancelled(taskId));
        }
        return cancelled;
    }

    Optional<DeepResearchTaskResponse> find(long taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    List<Long> runningTaskIdsFor(String userId) {
        if (userId == null) {
            return List.of();
        }
        return tasks.entrySet().stream()
                .filter(entry -> DeepResearchTaskResponse.RUNNING.equals(entry.getValue().status()))
                .filter(entry -> belongsTo(entry.getKey(), userId))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private record TaskHandle(String userId, Future<?> future) {
    }
}
