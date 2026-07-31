package com.agenttrail.loop.ppt;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** {@link PptTaskStore} 的内存实现（issue #24）——快速单测用，行为语义和 JDBC 实现完全一致。 */
public class InMemoryPptTaskStore implements PptTaskStore {

    private final Map<Long, PptTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    @Override
    public long create(String conversationId, PptGenerationContext initialContext) {
        long id = idSequence.getAndIncrement();
        long now = System.currentTimeMillis();
        tasks.put(id, new PptTask(id, conversationId, PptState.INIT, null,
                PptContextJson.toJson(initialContext), now, now));
        return id;
    }

    @Override
    public Optional<PptTask> findById(long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public void advance(long id, PptState newState, PptGenerationContext context) {
        tasks.compute(id, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("PPT 任务不存在: " + id);
            }
            return new PptTask(id, existing.conversationId(), newState, null,
                    PptContextJson.toJson(context), existing.createdAtMillis(), System.currentTimeMillis());
        });
    }

    @Override
    public void markFailed(long id, PptState failedState, String errorMsg) {
        tasks.compute(id, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("PPT 任务不存在: " + id);
            }
            return new PptTask(id, existing.conversationId(), failedState, errorMsg,
                    existing.contextJson(), existing.createdAtMillis(), System.currentTimeMillis());
        });
    }
}
