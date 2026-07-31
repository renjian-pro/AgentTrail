package com.agenttrail.legacy;

import com.agenttrail.legacy.V0.AgentLoop;
import com.agenttrail.legacy.V0.DeepSeekLlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits the real DeepSeek API — NOT picked up by `mvn test` (Surefire only matches
 * *Test.java by default; this class intentionally ends in "IT").
 * Run explicitly when you have a valid key in secrets.properties:
 *   ./mvnw.cmd test -Dtest=DeepSeekLlmClientLiveIT
 */
class DeepSeekLlmClientLiveIT {

    @Test
    void answersASimpleQuestionForRealAgainstDeepSeek() throws IOException {
        String apiKey = loadApiKey();
        DeepSeekLlmClient client = new DeepSeekLlmClient(apiKey, "https://api.deepseek.com", "deepseek-chat");
        AgentLoop loop = new AgentLoop(client, List.of(), 3);

        String answer = loop.run("Reply with exactly the word: pong");

        assertThat(answer).isNotBlank();
        System.out.println("DeepSeek live answer: " + answer);
    }

    private String loadApiKey() throws IOException {
        String fromEnv = System.getenv("DEEPSEEK_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(Path.of("secrets.properties"))) {
            props.load(in);
        }
        String fromFile = props.getProperty("DEEPSEEK_API_KEY");
        if (fromFile == null || fromFile.isBlank()) {
            throw new IllegalStateException("No DEEPSEEK_API_KEY in env or secrets.properties");
        }
        return fromFile;
    }
}
