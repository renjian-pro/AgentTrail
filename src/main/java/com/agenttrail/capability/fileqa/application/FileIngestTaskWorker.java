package com.agenttrail.capability.fileqa.application;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Owns asynchronous file ingestion; HTTP never waits for parsing or vectorization. */
public final class FileIngestTaskWorker {
    private final FileIngestUseCase ingest;
    private final Executor executor;

    public FileIngestTaskWorker(FileIngestUseCase ingest, Executor executor) {
        this.ingest = Objects.requireNonNull(ingest);
        this.executor = Objects.requireNonNull(executor);
    }

    public String submit(FileIngestUseCase.ReservedUpload reserved, byte[] content) {
        String taskId = UUID.randomUUID().toString();
        executor.execute(() -> ingest.completeReserved(reserved, content));
        return taskId;
    }
}
