package com.agenttrail.capability.fileqa.application;

import com.agenttrail.capability.file.FilePromptFormatter;
import com.agenttrail.capability.fileqa.port.FileStorePort;
import com.agenttrail.capability.fileqa.port.FileContextProvider;

public final class FileContextProviderImpl implements FileContextProvider {
    private final FileStorePort files;
    public FileContextProviderImpl(FileStorePort files) { this.files = files; }
    @Override public String contribute(String conversationId) {
        return FilePromptFormatter.formatAttachmentSection(files.findByConversationId(conversationId));
    }
    @Override public void onTurnCompleted(String conversationId, long turnId) {
        files.linkFilesToTurn(conversationId, turnId);
    }
}
