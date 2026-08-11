package com.agenttrail.capability.fileqa.port;

import com.agenttrail.capability.fileqa.domain.Attachment;

import java.util.List;
import java.util.Optional;

public interface FileStorePort {
    long save(Attachment attachment);
    Optional<Attachment> findById(long id);
    List<Attachment> findByConversationId(String conversationId);
    void updateParsedText(long id, String parsedText);

    /** Marks a successfully ingested attachment. Implementations may persist raw bytes when supported. */
    default void markReady(long id, String parsedText, byte[] rawBytes) {
        updateParsedText(id, parsedText);
    }

    /** Marks an attachment whose parsing or indexing failed. */
    default void markFailed(long id, String errorMessage) {
        // Legacy stores have no status/error columns; the adapter may override this when available.
    }
    void linkFilesToTurn(String conversationId, long turnId);
    void delete(long id);
}
