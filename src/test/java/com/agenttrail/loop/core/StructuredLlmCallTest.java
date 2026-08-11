package com.agenttrail.loop.core;

import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredLlmCallTest {

    @Test
    void repairsAndDeserializesTheExecutorResponse() {
        AgentLoopExecutor executor = mock(AgentLoopExecutor.class);
        when(executor.call(anyString(), any(RunnableParams.class)))
                .thenReturn("{name:\"alpha\",count:1,}");

        Payload payload = StructuredLlmCall.call(executor, "prompt",
                new RunnableParams("conversation", "stage", Map.of()), Payload.class);

        assertThat(payload).isEqualTo(new Payload("alpha", 1));
    }

    @Test
    void exposesRawAndRepairedJsonWhenDeserializationStillFails() {
        AgentLoopExecutor executor = mock(AgentLoopExecutor.class);
        when(executor.call(anyString(), any(RunnableParams.class))).thenReturn("not JSON");

        assertThatThrownBy(() -> StructuredLlmCall.call(executor, "prompt",
                new RunnableParams("conversation", "stage", Map.of()), Payload.class))
                .isInstanceOf(StructuredLlmCall.StructuredLlmCallException.class)
                .satisfies(failure -> {
                    StructuredLlmCall.StructuredLlmCallException typed =
                            (StructuredLlmCall.StructuredLlmCallException) failure;
                    assertThat(typed.rawJson()).isEqualTo("not JSON");
                    assertThat(typed.fixedJson()).contains("content");
                    assertThat(typed.getCause()).isNotNull();
                });
    }

    private record Payload(String name, int count) {
    }
}
