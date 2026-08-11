package com.agenttrail.capability.fileqa.port;

public interface FileContextProvider {
    String contribute(String conversationId);
    void onTurnCompleted(String conversationId, long turnId);
}
