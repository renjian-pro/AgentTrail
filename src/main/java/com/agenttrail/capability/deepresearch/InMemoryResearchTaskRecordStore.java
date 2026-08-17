package com.agenttrail.capability.deepresearch;

import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker.DeepResearchTaskStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试与"没配数据源"时的实现（issue #108 / R20）。
 *
 * <p>它当然不解决 R20 要解决的那个问题——重启就全没了。存在的意义是让不带数据库的装配
 * （单测、{@code DeepResearchController} 的兼容构造）不必判空，行为和这一票之前一致。
 */
public final class InMemoryResearchTaskRecordStore implements ResearchTaskRecordStore {

    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, ResearchTaskRecord> records = new ConcurrentHashMap<>();

    @Override
    public long create(String userId, String conversationId, String question) {
        long id = sequence.incrementAndGet();
        long now = System.currentTimeMillis();
        records.put(id, new ResearchTaskRecord(id, userId, conversationId, question,
                DeepResearchTaskStatus.RUNNING, null, now, now));
        return id;
    }

    @Override
    public void markTerminal(long id, DeepResearchTaskStatus status, String errorMsg) {
        records.computeIfPresent(id, (ignored, existing) -> existing.isRunning()
                ? new ResearchTaskRecord(existing.id(), existing.userId(), existing.conversationId(),
                        existing.question(), status, errorMsg,
                        existing.createdAtMillis(), System.currentTimeMillis())
                : existing);
    }

    @Override
    public Optional<ResearchTaskRecord> find(long id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public List<Long> runningIdsFor(String userId) {
        if (userId == null) {
            return List.of();
        }
        return records.values().stream()
                .filter(ResearchTaskRecord::isRunning)
                .filter(record -> Objects.equals(record.userId(), userId))
                .map(ResearchTaskRecord::id)
                .sorted()
                .toList();
    }

    /** 复用 {@link #markTerminal} 的"只改 RUNNING"语义，不再手抄一遍 record 的全部分量。 */
    @Override
    public int markRunningAsInterrupted() {
        List<Long> running = records.values().stream()
                .filter(ResearchTaskRecord::isRunning)
                .map(ResearchTaskRecord::id)
                .toList();
        running.forEach(id -> markTerminal(id, DeepResearchTaskStatus.FAILED,
                ResearchTaskRecord.INTERRUPTED_BY_RESTART));
        return running.size();
    }
}
