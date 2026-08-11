package com.agenttrail.capability.fileqa.application;

import com.agenttrail.capability.fileqa.domain.Attachment;
import com.agenttrail.capability.fileqa.port.RetrievalPort;

import java.util.List;

public final class FileRetrievalUseCase {
    private static final String NO_QUESTION = "文件较大，请携带具体问题进行检索";
    private static final String NO_RESULTS = "未检索到与问题相关的内容";
    private final RetrievalPort retrieval;
    private final int ragThresholdChars;

    public FileRetrievalUseCase(RetrievalPort retrieval, int ragThresholdChars) {
        this.retrieval = retrieval;
        this.ragThresholdChars = ragThresholdChars;
    }

    public String contentFor(Attachment file, String question) {
        String text = file.parsedText() == null ? "" : file.parsedText();
        if (text.length() <= ragThresholdChars) {
            return text;
        }
        if (question == null || question.isBlank()) {
            return NO_QUESTION;
        }
        List<String> matches = retrieval.retrieve(file.id(), question);
        return matches.isEmpty() ? NO_RESULTS : String.join("\n\n---\n\n", matches);
    }
}
