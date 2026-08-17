package com.agenttrail.capability.fileqa.application;

import com.agenttrail.capability.file.FileStore;
import com.agenttrail.capability.file.UploadedFile;
import com.agenttrail.capability.fileqa.domain.Attachment;
import com.agenttrail.capability.fileqa.domain.AttachmentStatus;
import com.agenttrail.capability.fileqa.port.FileStorePort;

import java.util.List;
import java.util.Optional;

public final class LegacyFileStoreAdapter implements FileStorePort {
    private final FileStore delegate;

    public LegacyFileStoreAdapter(FileStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public long save(Attachment attachment) {
        return delegate.save(new UploadedFile(null, attachment.userId(), attachment.conversationId(), attachment.turnId(),
                attachment.fileName(), attachment.contentType(), attachment.sizeBytes(), attachment.kind(),
                attachment.parsedText(), attachment.rawBytes(), attachment.createdAtMillis()));
    }

    @Override
    public Optional<Attachment> findById(long id) {
        return delegate.findById(id).map(LegacyFileStoreAdapter::fromLegacy);
    }

    @Override
    public List<Attachment> findByConversationId(String conversationId) {
        return delegate.findByConversationId(conversationId).stream().map(LegacyFileStoreAdapter::fromLegacy).toList();
    }

    @Override public void updateParsedText(long id, String parsedText) { delegate.updateParsedText(id, parsedText); }
    @Override public void linkFilesToTurn(String conversationId, java.util.List<Long> fileIds, long turnId) {
        delegate.linkFilesToTurn(conversationId, fileIds, turnId);
    }
    @Override public void delete(long id) { delegate.delete(id); }

    private static Attachment fromLegacy(UploadedFile file) {
        return new Attachment(file.id(), file.userId(), file.conversationId(), file.turnId(), file.fileName(),
                file.contentType(), file.sizeBytes(), file.kind(), AttachmentStatus.READY, file.parsedText(),
                file.rawBytes(), null, file.createdAtMillis());
    }
}
