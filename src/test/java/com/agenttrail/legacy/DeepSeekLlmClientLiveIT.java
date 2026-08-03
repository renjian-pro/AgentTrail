package com.agenttrail.legacy;

import com.agenttrail.legacy.V0.AgentLoop;
import com.agenttrail.legacy.V0.DeepSeekLlmClient;
import com.agenttrail.support.LocalConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits the real DeepSeek API — NOT picked up by `mvn test` (Surefire only matches
 * *Test.java by default; this class intentionally ends in "IT").
 * Run explicitly when you have a valid key in application-local.yml:
 *   ./mvnw.cmd test -Dtest=DeepSeekLlmClientLiveIT
 */
class DeepSeekLlmClientLiveIT {

    @Test
    void answersASimpleQuestionForRealAgainstDeepSeek() {
        String apiKey = LocalConfig.require("spring.ai.deepseek.api-key");
        DeepSeekLlmClient client = new DeepSeekLlmClient(apiKey, "https://api.deepseek.com", "deepseek-chat");
        AgentLoop loop = new AgentLoop(client, List.of(), 3);

        String answer = loop.run("Reply with exactly the word: pong");

        assertThat(answer).isNotBlank();
        System.out.println("DeepSeek live answer: " + answer);
    }
}
