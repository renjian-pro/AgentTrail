package com.agenttrail.console;

import com.agenttrail.loop.AgentLoop;
import com.agenttrail.loop.deepseek.DeepSeekLlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Interactive console entry point. Not active by default so it never blocks reading
 * System.in during normal app startup or tests -- run with the "console" profile:
 *   ./mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=console
 * or set the "console" active profile in the IDEA run configuration.
 */
@Component
@Profile("console")
public class AgentConsoleRunner implements CommandLineRunner {

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public AgentConsoleRunner(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.model}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public void run(String... args) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("No deepseek.api-key configured. Set DEEPSEEK_API_KEY in secrets.properties. Exiting.");
            return;
        }

        AgentLoop agentLoop = new AgentLoop(new DeepSeekLlmClient(apiKey, baseUrl, model), List.of(), 5);
        ConsoleSession session = new ConsoleSession(agentLoop);

        System.out.println("AgentTrail console. Type a message and press Enter (type 'exit' to quit).");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            session.run(reader, System.out);
        }
    }
}
