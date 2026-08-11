package com.agenttrail.capability.ppt;

import com.agenttrail.platform.error.RetryClass;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PptFailureClassifierTest {
    @Test
    void classifiesRenderTimeoutAsRetriable() {
        assertThat(PptFailureClassifier.classify(new PptRenderException("render timeout")))
                .isEqualTo(RetryClass.RETRIABLE);
    }

    @Test
    void classifiesSchemaFailureAsFatal() {
        assertThat(PptFailureClassifier.classify(new IllegalArgumentException("invalid schema")))
                .isEqualTo(RetryClass.FATAL);
    }
}
