package com.agenttrail.capability.fileqa.application;

import com.agenttrail.capability.file.FileKind;
import com.agenttrail.capability.file.FileTextParser;
import com.agenttrail.capability.file.IngestedFile;
import com.agenttrail.capability.fileqa.domain.Attachment;
import com.agenttrail.capability.fileqa.domain.AttachmentStatus;
import com.agenttrail.capability.fileqa.port.EmbeddingPort;
import com.agenttrail.capability.fileqa.port.FileStorePort;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.ByteArrayInputStream;
import java.time.Clock;

public final class FileIngestUseCase {
    private final FileStorePort files;
    private final FileTextParser parser;
    private final EmbeddingPort embeddings;
    private final int ragThresholdChars;
    private final Clock clock;

    public FileIngestUseCase(FileStorePort files, FileTextParser parser, EmbeddingPort embeddings,
                             int ragThresholdChars) {
        this(files, parser, embeddings, ragThresholdChars, Clock.systemUTC());
    }

    public FileIngestUseCase(FileStorePort files, FileTextParser parser, EmbeddingPort embeddings,
                             int ragThresholdChars, Clock clock) {
        this.files = files;
        this.parser = parser;
        this.embeddings = embeddings;
        this.ragThresholdChars = ragThresholdChars;
        this.clock = clock;
    }

    public IngestedFile ingest(String userId, String conversationId, String fileName, String contentType,
                               InputStream content, long sizeBytes) {
        byte[] raw = null;
        String parsedText = null;
        FileKind kind = com.agenttrail.capability.file.FileKindDetector.detect(contentType, fileName);
        if (kind == FileKind.IMAGE) {
            raw = readAllBytes(content);
        } else {
            parsedText = parser.parse(content, fileName);
        }
        Attachment attachment = new Attachment(null, userId, conversationId, null, fileName, contentType,
                sizeBytes, kind, AttachmentStatus.INGESTING, parsedText, raw, null, clock.millis());
        long id = files.save(attachment);
        try {
            if (kind == FileKind.TEXT && parsedText.length() > ragThresholdChars) {
                embeddings.embedAndStore(id, parsedText);
                files.markReady(id, parsedText, raw);
                return new IngestedFile(id, fileName, kind, sizeBytes, parsedText.length(), true);
            }
            files.markReady(id, parsedText, raw);
            return new IngestedFile(id, fileName, kind, sizeBytes, parsedText == null ? 0 : parsedText.length(), false);
        } catch (RuntimeException failure) {
            files.markFailed(id, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            throw failure;
        }
    }

    /** Reserve the durable file id before expensive parsing/indexing starts. */
    public ReservedUpload reserve(String userId, String conversationId, String fileName,
                                  String contentType, long sizeBytes) {
        FileKind kind = com.agenttrail.capability.file.FileKindDetector.detect(contentType, fileName);
        Attachment attachment = new Attachment(null, userId, conversationId, null, fileName, contentType,
                sizeBytes, kind, AttachmentStatus.INGESTING, null, null, null, clock.millis());
        long id = files.save(attachment);
        return new ReservedUpload(id, fileName, kind, sizeBytes);
    }

    /** Complete a reservation on a worker thread; only text uploads use this path today. */
    public IngestedFile completeReserved(ReservedUpload reserved, byte[] content) {
        try {
            IngestedFile result = ingestReserved(reserved, new ByteArrayInputStream(content));
            return result;
        } catch (RuntimeException failure) {
            files.markFailed(reserved.fileId(), failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
            throw failure;
        }
    }

    private IngestedFile ingestReserved(ReservedUpload reserved, InputStream content) {
        if (reserved.kind() == FileKind.IMAGE) {
            byte[] raw = readAllBytes(content);
            files.markReady(reserved.fileId(), null, raw);
            return new IngestedFile(reserved.fileId(), reserved.fileName(), reserved.kind(),
                    reserved.sizeBytes(), 0, false);
        }
        String parsedText = parser.parse(content, reserved.fileName());
        boolean routedToRag = parsedText.length() > ragThresholdChars;
        if (routedToRag) embeddings.embedAndStore(reserved.fileId(), parsedText);
        files.markReady(reserved.fileId(), parsedText, null);
        return new IngestedFile(reserved.fileId(), reserved.fileName(), reserved.kind(), reserved.sizeBytes(),
                parsedText.length(), routedToRag);
    }

    public void delete(long fileId) {
        files.findById(fileId).ifPresent(attachment -> {
            if (attachment.kind() == FileKind.TEXT && attachment.parsedText() != null
                    && attachment.parsedText().length() > ragThresholdChars) {
                embeddings.deleteByFileId(fileId);
            }
            files.delete(fileId);
        });
    }

    private static byte[] readAllBytes(InputStream content) {
        try {
            return content.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public record ReservedUpload(long fileId, String fileName, FileKind kind, long sizeBytes) { }
}
