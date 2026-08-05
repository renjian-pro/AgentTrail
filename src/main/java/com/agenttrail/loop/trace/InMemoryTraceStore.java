package com.agenttrail.loop.trace;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内存版 {@link TraceStore}——每个会话一份追加写的列表，无需外部存储即可跑通全流程。 */
public class InMemoryTraceStore implements TraceStore {

    private final Map<String, List<TraceRecord>> recordsByConversation = new ConcurrentHashMap<>();

    @Override
    public void save(TraceRecord record) {
        recordsByConversation
                .computeIfAbsent(record.conversationId(), ignored -> new CopyOnWriteArrayList<>())
                .add(record);
    }

    @Override
    public List<TraceRecord> findByConversationId(String conversationId) {
        return List.copyOf(recordsByConversation.getOrDefault(conversationId, List.of()));
    }

    @Override
    public Optional<Integer> verifyChain(String conversationId) {
        return Optional.empty();
    }
}
