package com.agenttrail.loop.file;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 进程内存版 {@link FileStore}——无需外部存储即可跑通全流程，主键用自增序号模拟。 */
public class InMemoryFileStore implements FileStore {

    private final Map<Long, UploadedFile> filesById = new ConcurrentHashMap<>();
    private final Map<String, List<UploadedFile>> filesByConversation = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public long save(UploadedFile file) {
        long id = nextId.getAndIncrement();
        UploadedFile saved = new UploadedFile(id, file.conversationId(), file.turnId(), file.fileName(),
                file.contentType(), file.sizeBytes(), file.parsedText(), file.createdAtMillis());
        filesById.put(id, saved);
        filesByConversation.computeIfAbsent(file.conversationId(), ignored -> new CopyOnWriteArrayList<>())
                .add(saved);
        return id;
    }

    @Override
    public Optional<UploadedFile> findById(long id) {
        return Optional.ofNullable(filesById.get(id));
    }

    @Override
    public List<UploadedFile> findByConversationId(String conversationId) {
        return List.copyOf(filesByConversation.getOrDefault(conversationId, List.of()));
    }
}
