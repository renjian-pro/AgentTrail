package com.agenttrail.evaluation;

import com.agenttrail.legacy.V0.AgentScopeRuntime;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic Golden Task runs through the real AgentScope Java adapter. */
class AgentScopeRuntimeProofTest {
    private HttpServer modelServer;

    @BeforeEach
    void startDeterministicOpenAiCompatibleModel() throws IOException {
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext("/", exchange -> {
            byte[] body = "{\"id\":\"proof\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"proof\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"golden task completed\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":3,\"total_tokens\":4}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        modelServer.start();
    }

    @AfterEach
    void stopModelServer() {
        modelServer.stop(0);
    }

    @Test
    void executesGoldenTasksThroughARealAgentScopeRuntime() {
        AgentScopeRuntime runtime = new AgentScopeRuntime("proof-key",
                "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1", "proof-model", 3);

        List<GoldenCase> cases = GoldenTaskRunner.loadAll().stream()
                .filter(testCase -> List.of("repro-001", "repro-002", "repro-003").contains(testCase.id()))
                .toList();
        long startedAt = System.nanoTime();
        GoldenTaskReport report = GoldenTaskRunner.runCaseList(cases, testCase -> {
            long caseStartedAt = System.nanoTime();
            String answer = runtime.respond(testCase.question());
            return new GoldenTaskReport.GoldenObservation(testCase.id(), testCase.dimension(),
                    answer.contains("golden task completed"), "", 1,
                    (System.nanoTime() - caseStartedAt) / 1_000_000,
                    testCase.referenceSql(), answer, List.of(), Map.of(), testCase.question());
        });

        System.out.println("AgentScope Java Golden Tasks (elapsedMs="
                + (System.nanoTime() - startedAt) / 1_000_000 + ")\n" + report.markdown());
        assertThat(cases).hasSize(3);
        assertThat(report.passRate()).isEqualTo(1.0);
    }
}
