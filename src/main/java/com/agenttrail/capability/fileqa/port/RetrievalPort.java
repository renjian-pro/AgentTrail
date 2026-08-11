package com.agenttrail.capability.fileqa.port;

import java.util.List;

public interface RetrievalPort {
    List<String> retrieve(long fileId, String question);
}
