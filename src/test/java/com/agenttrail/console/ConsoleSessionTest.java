package com.agenttrail.console;

import com.agenttrail.loop.AgentLoop;
import com.agenttrail.loop.LlmResponse;
import com.agenttrail.loop.ToolCallRequest;
import com.agenttrail.loop.support.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleSessionTest {

    @Test
    void printsAgentAnswerForEachLineUntilExitCommand() throws IOException {
        ScriptedLlmClient llmClient = new ScriptedLlmClient(List.of(
                new LlmResponse.FinalAnswer("hi there")
        ));
        AgentLoop loop = new AgentLoop(llmClient, List.of(), 5);
        ConsoleSession session = new ConsoleSession(loop);

        String printed = runSession(session, "hello\nexit\n");

        assertThat(printed).contains("hi there");
        assertThat(printed).contains("Bye.");
    }

    @Test
    void skipsBlankLinesWithoutCallingTheAgent() throws IOException {
        ScriptedLlmClient llmClient = new ScriptedLlmClient(List.of(
                new LlmResponse.FinalAnswer("answer")
        ));
        AgentLoop loop = new AgentLoop(llmClient, List.of(), 5);
        ConsoleSession session = new ConsoleSession(loop);

        runSession(session, "\n   \nask something\nexit\n");

        assertThat(llmClient.callCount()).isEqualTo(1);
    }

    @Test
    void printsErrorMessageInsteadOfCrashingWhenAgentLoopThrows() throws IOException {
        ScriptedLlmClient llmClient = new ScriptedLlmClient(List.of(
                new LlmResponse.ToolCall(new ToolCallRequest("missing-tool", Map.of()))
        ));
        AgentLoop loop = new AgentLoop(llmClient, List.of(), 5);
        ConsoleSession session = new ConsoleSession(loop);

        String printed = runSession(session, "do it\nexit\n");

        assertThat(printed).contains("Error:");
        assertThat(printed).contains("missing-tool");
    }

    @Test
    void stopsGracefullyWhenInputEndsWithoutAnExitCommand() throws IOException {
        ScriptedLlmClient llmClient = new ScriptedLlmClient(List.of(
                new LlmResponse.FinalAnswer("only answer")
        ));
        AgentLoop loop = new AgentLoop(llmClient, List.of(), 5);
        ConsoleSession session = new ConsoleSession(loop);

        String printed = runSession(session, "one question\n");

        assertThat(printed).contains("only answer");
    }

    private String runSession(ConsoleSession session, String scriptedInput) throws IOException {
        BufferedReader input = new BufferedReader(new StringReader(scriptedInput));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        session.run(input, output);

        return buffer.toString(StandardCharsets.UTF_8);
    }
}
