package com.agenttrail.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.capability.chat.application.ChatApplicationService;
import com.agenttrail.capability.chat.application.PausedRunPort;
import com.agenttrail.capability.chat.application.RuntimeProfileRegistry;
import com.agenttrail.conversation.application.ConversationPort;
import com.agenttrail.infrastructure.runtime.LegacyAgentLoopExecutorAdapter;
import com.agenttrail.infrastructure.runtime.PauseStatePausedRunAdapter;
import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.hook.ToolRiskRegistry;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.pause.JdbcPauseStateStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.tools.ResumeSafePoint;
import com.agenttrail.runtime.api.AgentRuntimePort;
import com.agenttrail.support.MySqlContainerTestSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 通过真实 HTTP、Spring MVC 与 MySQL 验证审批恢复的外部契约。 */
@Testcontainers
class AgentApprovalHttpIT extends MySqlContainerTestSupport {

    private static DataSource dataSource;
    private static AgentRuntimePort activeRuntime;
    private static PausedRunPort activePausedRuns;

    private ConfigurableApplicationContext application;
    private int port;

    @BeforeAll
    static void createSchema() {
        dataSource = createDataSourceAndSchema();
    }

    @BeforeEach
    void resetTables() {
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_session").update();
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_pause_state").update();
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_tool_idempotency").update();
    }

    @AfterEach
    void stopApplication() {
        if (application != null) {
            application.close();
        }
    }

    @Test
    void approvedToolSurvivesAfterCheckpointRestartWithoutExecutingTwice() throws Exception {
        String conversationId = "conv-http-after-recovery";
        RecordingToolCallback tool = new RecordingToolCallback("chargeCard", "charges a card", "charged");
        JdbcPauseStateStore pausingStore = new JdbcPauseStateStore(dataSource);
        AgentLoopExecutor pausingExecutor = AgentLoopExecutor.builder(
                        new ScriptedChatModel(List.of(toolCall("call-http", "chargeCard", "{\"amount\":100}"))),
                        List.of(tool), 5)
                .pauseConfig(new PauseConfig(java.util.Set.of("chargeCard"), pausingStore))
                .modelName("deepseek-chat")
                .build();
        pausingExecutor.stream("充值 100 元", new RunnableParams(conversationId, "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        ScriptedChatModel failingModel = new ScriptedChatModel(List.of()) {
            @Override
            public Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                return Flux.error(new IllegalStateException("provider unavailable"));
            }
        };
        JdbcPauseStateStore firstRuntimeStore = new JdbcPauseStateStore(dataSource);
        AgentTaskManager firstTasks = new AgentTaskManager();
        startApplication(runtime(failingModel, tool, firstRuntimeStore, firstTasks), firstRuntimeStore);

        HttpResponse<String> failedApproval = approve(conversationId);
        assertThat(failedApproval.statusCode()).as(failedApproval.body()).isEqualTo(200);
        assertThat(failedApproval.body()).contains("RunFailed", "provider unavailable");
        assertThat(tool.recordedArguments()).containsExactly("{\"amount\":100}");
        assertThat(firstRuntimeStore.find(conversationId)).get()
                .extracting(state -> state.safePoint()).isEqualTo(ResumeSafePoint.AFTER_TOOL_EXECUTION);

        // 模拟服务进程重启：关闭旧 Spring 上下文，并用全新的 Store、TaskManager 和执行器接手快照。
        application.close();
        application = null;
        JdbcPauseStateStore restartedStore = new JdbcPauseStateStore(dataSource);
        AgentTaskManager restartedTasks = new AgentTaskManager();
        startApplication(runtime(new ScriptedChatModel(List.of(text("充值成功"))), tool,
                restartedStore, restartedTasks), restartedStore);

        HttpResponse<String> lookup = client().send(HttpRequest.newBuilder(uri(
                        "/agent/v1/chat/%s/pause".formatted(conversationId))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(lookup.statusCode()).isEqualTo(200);
        assertThat(lookup.body()).contains("AFTER_TOOL_EXECUTION");

        HttpResponse<String> recovered = approve(conversationId);
        assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(200);
        assertThat(recovered.body()).contains("RunCompleted", "充值成功");
        assertThat(tool.recordedArguments())
                .as("AFTER 检查点恢复只能继续模型调用，不能重复产生工具副作用")
                .containsExactly("{\"amount\":100}");
        assertThat(restartedStore.find(conversationId)).isEmpty();
    }

    @Test
    void boundaryReturnsNotFoundBadRequestAndConflictStatuses() throws Exception {
        RecordingToolCallback tool = new RecordingToolCallback("chargeCard", "charges a card", "charged");
        JdbcPauseStateStore store = new JdbcPauseStateStore(dataSource);
        AgentTaskManager tasks = new AgentTaskManager();
        startApplication(runtime(new ScriptedChatModel(List.of(text("完成"))), tool, store, tasks), store);

        HttpClient client = client();
        assertThat(client.send(HttpRequest.newBuilder(uri("/agent/v1/chat/missing/pause")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertThat(client.send(HttpRequest.newBuilder(uri("/agent/v1/chat/missing/approve"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("not-json")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);

        String conversationId = "conv-http-conflict";
        AgentLoopExecutor.builder(
                        new ScriptedChatModel(List.of(toolCall("call-conflict", "chargeCard", "{}"))),
                        List.of(tool), 5)
                .pauseConfig(new PauseConfig(java.util.Set.of("chargeCard"), store))
                .modelName("deepseek-chat")
                .build()
                .stream("充值", new RunnableParams(conversationId, "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        Sinks.Many<AgentStreamEvent> occupied = Sinks.many().multicast().onBackpressureBuffer();
        assertThat(tasks.registerTask(conversationId, occupied)).isTrue();
        try {
            HttpResponse<String> conflict = approve(conversationId);
            assertThat(conflict.statusCode()).as(conflict.body()).isEqualTo(409);
        } finally {
            tasks.removeTask(conversationId);
        }
    }

    private static AgentRuntimePort runtime(ScriptedChatModel model, RecordingToolCallback tool,
                                            JdbcPauseStateStore store, AgentTaskManager tasks) {
        AgentLoopExecutor executor = AgentLoopExecutor.builder(model, List.of(tool), 5)
                .taskManager(tasks)
                .pauseConfig(new PauseConfig(java.util.Set.of("chargeCard"), store))
                .modelName("deepseek-chat")
                .build();
        return new LegacyAgentLoopExecutorAdapter(executor, tasks);
    }

    private void startApplication(AgentRuntimePort runtime, JdbcPauseStateStore store) {
        activeRuntime = runtime;
        activePausedRuns = new PauseStatePausedRunAdapter(store, ToolRiskRegistry.defaults());
        application = new SpringApplicationBuilder(HttpTestApplication.class)
                .properties("server.port=0", "spring.main.banner-mode=off", "logging.level.root=ERROR",
                        "spring.ai.deepseek.api-key=test-only", "spring.ai.openai.api-key=test-only",
                        "management.endpoint.health.validate-group-membership=false")
                .run();
        port = ((ServletWebServerApplicationContext) application).getWebServer().getPort();
    }

    private HttpResponse<String> approve(String conversationId) throws Exception {
        return client().send(HttpRequest.newBuilder(uri(
                        "/agent/v1/chat/%s/approve".formatted(conversationId)))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"approved":true,"rejectionReason":null,"modelId":null,
                                 "webSearchEnabled":false,"mode":null}
                                """))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient client() {
        return HttpClient.newHttpClient();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:%d%s".formatted(port, path));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import(AgentLoopController.class)
    static class HttpTestApplication {

        @Bean
        ChatApplicationService chatApplicationService() {
            return new ChatApplicationService(
                    new RuntimeProfileRegistry(Map.of("qwen-plus", activeRuntime), "qwen-plus"),
                    mock(ConversationPort.class), activePausedRuns);
        }

        @Bean
        WebMvcConfigurer testPrincipalInterceptor() {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override
                        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                                 Object handler) {
                            // DispatcherServlet 绑定请求上下文后再建立主体，Controller 仍走生产的鉴权路径。
                            StpUtil.login("user-1");
                            return true;
                        }
                    });
                }
            };
        }
    }
}
