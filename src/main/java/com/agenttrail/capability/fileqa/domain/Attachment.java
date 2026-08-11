package com.agenttrail.capability.fileqa.domain;

import com.agenttrail.capability.file.FileKind;

import java.util.Arrays;

public record Attachment(Long id, String userId, String conversationId, Long turnId,
                         String fileName, String contentType, long sizeBytes, FileKind kind,
                         AttachmentStatus status, String parsedText, byte[] rawBytes,
                         String errorCode, long createdAtMillis) {

    public Attachment {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be non-negative");
        }
        rawBytes = rawBytes == null ? null : Arrays.copyOf(rawBytes, rawBytes.length);
    }

    public Attachment linkedToTurn(long turnId) {
        return new Attachment(id, userId, conversationId, turnId, fileName, contentType, sizeBytes, kind,
                status, parsedText, rawBytes, errorCode, createdAtMillis);
    }

    public Attachment markReady(String parsedText) {
        return new Attachment(id, userId, conversationId, turnId, fileName, contentType, sizeBytes, kind,
                AttachmentStatus.READY, parsedText, rawBytes, null, createdAtMillis);
    }

    public Attachment markFailed(String errorCode) {
        return new Attachment(id, userId, conversationId, turnId, fileName, contentType, sizeBytes, kind,
                AttachmentStatus.FAILED, parsedText, rawBytes, errorCode, createdAtMillis);
    }

    @Override
    public byte[] rawBytes() {
        return rawBytes == null ? null : Arrays.copyOf(rawBytes, rawBytes.length);
    }
}
