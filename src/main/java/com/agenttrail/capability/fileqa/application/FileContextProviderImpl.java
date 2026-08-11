package com.agenttrail.capability.fileqa.application;

import com.agenttrail.capability.file.FilePromptFormatter;
import com.agenttrail.capability.file.FileStore;
import com.agenttrail.capability.fileqa.port.FileContextProvider;

public final class FileContextProviderImpl implements FileContextProvider {
    private final FileStore files;
    public FileContextProviderImpl(FileStore files) { this.files = files; }
    @Override public String contribute(String conversationId) {
        return FilePromptFormatter.formatSection(files.findByConversationId(conversationId));
    }
    @Override public void onTurnCompleted(String conversationId, long turnId) {
        files.linkFilesToTurn(conversationId, turnId);
    }
}
