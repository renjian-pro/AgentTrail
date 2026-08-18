package com.agenttrail.capability.ppt;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@link PptTaskStore} 的内存实现（issue #24）——快速单测用，行为语义和 JDBC 实现完全一致。 */
public class InMemoryPptTaskStore implements PptTaskStore {

    private final Map<Long, PptTask> tasks = new ConcurrentHashMap<>();
    private final Map<Long, List<PptCheckpointEvent>> events = new ConcurrentHashMap<>();
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();
    private final AtomicLong idSequence = new AtomicLong(1);

    @Override
    public long create(String userId, String conversationId, PptGenerationContext initialContext) {
        long id = idSequence.getAndIncrement();
        long now = System.currentTimeMillis();
        tasks.put(id, new PptTask(id, userId, conversationId, PptState.INIT, PptRunStatus.QUEUED, null,
                PptContextJson.toJson(initialContext), initialContext.contextVersion(), 0, now, now));
        events.put(id, new ArrayList<>());
        return id;
    }

    @Override
    public Optional<PptTask> findById(long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public Optional<PptTask> findLatestByConversationId(String conversationId) {
        // 主键是 AtomicLong 递增分配的，id 越大就是越晚创建——用 id 排序当"最新"，
        // 不用 createdAtMillis（同一毫秒内可能有并列，id 顺序才是唯一确定的创建先后）。
        return tasks.values().stream()
                .filter(task -> task.conversationId().equals(conversationId))
                .max(java.util.Comparator.comparingLong(PptTask::id));
    }

    @Override
    public boolean conditionalAdvance(long id, PptState expectedState, long expectedRevision,
            PptState newState, PptRunStatus newRunStatus, PptGenerationContext context) {
        AtomicBoolean updated = new AtomicBoolean();
        tasks.compute(id, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("PPT 任务不存在: " + id);
            }
            if (existing.status() != expectedState || existing.revision() != expectedRevision) {
                return existing;
            }
            long now = System.currentTimeMillis();
            long nextRevision = existing.revision() + 1;
            appendEvent(existing, expectedState, PptCheckpointEvent.OUTCOME_SUCCEEDED,
                    now, now, null, nextRevision);
            updated.set(true);
            return new PptTask(id, existing.userId(), existing.conversationId(), newState, newRunStatus, null,
                    PptContextJson.toJson(context), context.contextVersion(), nextRevision,
                    existing.createdAtMillis(), now);
        });
        return updated.get();
    }

    @Override
    public boolean markFailedIfCurrent(long id, PptState expectedState, long expectedRevision,
            PptState failedState, String errorMsg) {
        AtomicBoolean updated = new AtomicBoolean();
        tasks.compute(id, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("PPT 任务不存在: " + id);
            }
            if (existing.status() != expectedState || existing.revision() != expectedRevision) {
                return existing;
            }
            long now = System.currentTimeMillis();
            long nextRevision = existing.revision() + 1;
            appendEvent(existing, failedState, PptCheckpointEvent.OUTCOME_FAILED,
                    now, now, errorMsg, nextRevision);
            updated.set(true);
            return new PptTask(id, existing.userId(), existing.conversationId(), failedState, PptRunStatus.FAILED,
                    errorMsg, existing.contextJson(), existing.contextVersion(), nextRevision,
                    existing.createdAtMillis(), now);
        });
        return updated.get();
    }

    @Override
    public List<PptCheckpointEvent> eventsForTask(long taskId) {
        List<PptCheckpointEvent> taskEvents = events.get(taskId);
        if (taskEvents == null) {
            return List.of();
        }
        synchronized (taskEvents) {
            return List.copyOf(taskEvents);
        }
    }

    @Override
    public void requestCancel(long id) {
        tasks.compute(id, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("PPT 任务不存在: " + id);
            }
            if (existing.status() == PptState.SUCCESS || existing.status() == PptState.CANCELLED) {
                return existing;
            }
            if (existing.runStatus() == PptRunStatus.CANCEL_REQUESTED) {
                cancelRequested.add(id);
                return existing;
            }
            cancelRequested.add(id);
            return new PptTask(id, existing.userId(), existing.conversationId(), existing.status(),
                    PptRunStatus.CANCEL_REQUESTED, existing.errorMsg(), existing.contextJson(),
                    existing.contextVersion(), existing.revision() + 1,
                    existing.createdAtMillis(), System.currentTimeMillis());
        });
    }

    @Override
    public void markCancelled(long id, PptState atState) {
        tasks.compute(id, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("PPT 任务不存在: " + id);
            }
            if (existing.status() == PptState.SUCCESS || existing.status() == PptState.CANCELLED) {
                return existing;
            }
            long now = System.currentTimeMillis();
            long nextRevision = existing.revision() + 1;
            appendEvent(existing, atState, PptCheckpointEvent.OUTCOME_CANCELLED,
                    now, now, null, nextRevision);
            return new PptTask(id, existing.userId(), existing.conversationId(), PptState.CANCELLED,
                    PptRunStatus.CANCELLED, null, existing.contextJson(), existing.contextVersion(),
                    nextRevision, existing.createdAtMillis(), now);
        });
        cancelRequested.remove(id);
    }

    @Override
    public boolean isCancelRequested(long id) {
        return cancelRequested.contains(id);
    }

    @Override
    public List<Long> runningTaskIdsFor(String userId) {
        if (userId == null) {
            return List.of();
        }
        return tasks.values().stream()
                .filter(task -> userId.equals(task.userId()))
                // AWAITING_INPUT 排除在外：它没出错，但没有用户补充就推不动，算不上"仍可继续推进"
                .filter(task -> task.runStatus() == PptRunStatus.QUEUED
                        || task.runStatus() == PptRunStatus.RUNNING
                        || task.runStatus() == PptRunStatus.RETRY_WAIT)
                .map(PptTask::id)
                .sorted()
                .toList();
    }

    private void appendEvent(PptTask task, PptState stage, String outcome, long startedAt, long finishedAt,
            String errorMessage, long revisionAfter) {
        List<PptCheckpointEvent> taskEvents = events.computeIfAbsent(task.id(), ignored -> new ArrayList<>());
        synchronized (taskEvents) {
            int attempt = (int) taskEvents.stream().filter(event -> event.stage() == stage).count() + 1;
            taskEvents.add(new PptCheckpointEvent(task.id(), stage, attempt, startedAt, finishedAt, outcome,
                    null, null, errorMessage == null ? null : "PPT_STAGE_FAILED", null, null, null, null,
                    task.revision(), revisionAfter));
        }
    }
}
