package com.agenttrail.loop.tools.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 一个远端 MCP 服务的会话，负责"懒建连 + 只缓存成功结果 + 会话死掉能自愈"，
 * 给 {@code chart.ChartToolProvider} 和 {@code websearch.TavilySearchToolProvider} 共用。
 *
 * <p><b>为什么返回的是代理而不是 MCP 客户端本身给出的 ToolCallback</b>：
 * {@code AgentLoopExecutorFactory} 会把工具列表**烤进**它自己缓存的执行器对象里
 * （{@code analyticsExecutorsByModelId}/{@code webSearchExecutorsByModelId}）。如果这里直接
 * 交出底层 callback，那么远端服务重启之后，即使这个类重新建连、拿到一批新的 callback，
 * 那些早就被缓存住的执行器也永远看不到——只能等应用重启。真实踩过：mcp-echarts 容器重建后
 * 每次画图都报 {@code Server does not recognize session ...} / {@code MCP session with server
 * terminated}，重启应用才好。所以对外交出的是**长期有效的代理**，代理每次调用时才去解析当前
 * 活着的委托对象，会话换了也不用换代理对象。
 *
 * <p>只重试一次：远端真的挂了的时候不能变成无限重连，外层还有
 * {@code AgentLoopExecutor} 的 {@code maxConsecutiveToolFailures} 兜底。
 */
public final class McpToolSession {

    private static final Logger log = LoggerFactory.getLogger(McpToolSession.class);

    /** 建连动作本身，由各 provider 提供（拼 transport、带鉴权头等各不相同）。允许抛异常表示本次建连失败。 */
    @FunctionalInterface
    public interface Connector {
        List<ToolCallback> connect() throws Exception;
    }

    private final String description;
    private final Connector connector;
    private final int maxAttempts;

    private final Object lock = new Object();
    private volatile Map<String, ToolCallback> delegatesByName;

    public McpToolSession(String description, Connector connector, int maxAttempts) {
        this.description = description;
        this.connector = connector;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 本次可用的工具列表；远端连不上时返回空列表（调用方据此决定"这次对话压根不挂这个工具"），
     * 失败不缓存，下次调用会重新尝试。
     */
    public List<ToolCallback> toolCallbacks() {
        Map<String, ToolCallback> delegates = connectIfNeeded();
        if (delegates == null) {
            return List.of();
        }
        return delegates.values().stream()
                .map(delegate -> (ToolCallback) new ReconnectingToolCallback(
                        this, delegate.getToolDefinition(), delegate.getToolMetadata()))
                .toList();
    }

    /** 丢弃当前会话，下一次解析工具时重新建连。 */
    public void invalidate() {
        synchronized (lock) {
            delegatesByName = null;
        }
    }

    private String invoke(String toolName, Function<ToolCallback, String> call) {
        try {
            return call.apply(resolve(toolName));
        } catch (RuntimeException failure) {
            if (!isSessionTerminated(failure)) {
                throw failure;
            }
            log.warn("{} 的 MCP 会话已失效（{}），重建会话后重试一次", description, failure.getMessage());
            invalidate();
            return call.apply(resolve(toolName));
        }
    }

    private ToolCallback resolve(String toolName) {
        Map<String, ToolCallback> delegates = connectIfNeeded();
        if (delegates == null) {
            throw new IllegalStateException(description + " 当前不可用，无法调用工具 " + toolName);
        }
        ToolCallback delegate = delegates.get(toolName);
        if (delegate == null) {
            throw new IllegalStateException(description + " 重建会话后不再提供工具 " + toolName);
        }
        return delegate;
    }

    /** @return 建连失败时返回 null，交给调用方决定是降级成空列表还是抛异常 */
    private Map<String, ToolCallback> connectIfNeeded() {
        Map<String, ToolCallback> current = delegatesByName;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (delegatesByName != null) {
                return delegatesByName;
            }
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    Map<String, ToolCallback> connected = new LinkedHashMap<>();
                    for (ToolCallback delegate : connector.connect()) {
                        connected.put(delegate.getToolDefinition().name(), delegate);
                    }
                    delegatesByName = connected;
                    return connected;
                } catch (Exception failure) {
                    log.warn("{} MCP 客户端初始化失败（第 {}/{} 次）：{}",
                            description, attempt, maxAttempts, failure.getMessage());
                }
            }
            log.warn("{} MCP 客户端连续 {} 次初始化失败，本次对话该工具不可用", description, maxAttempts);
            return null;
        }
    }

    /**
     * 区分"会话死了，重连就能好"和"这次调用本身失败了"——后者（参数非法、工具内部报错）
     * 必须原样抛给上层，重连解决不了，吞掉只会掩盖真实错误。
     */
    private static boolean isSessionTerminated(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("session with server terminated")
                    || message.contains("does not recognize session"))) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /** 对外长期有效的工具代理：定义信息建连时抓一次，实际调用每次现解析当前会话的委托对象。 */
    private static final class ReconnectingToolCallback implements ToolCallback {

        private final McpToolSession session;
        private final ToolDefinition definition;
        private final ToolMetadata metadata;

        private ReconnectingToolCallback(McpToolSession session, ToolDefinition definition, ToolMetadata metadata) {
            this.session = session;
            this.definition = definition;
            this.metadata = metadata;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return metadata;
        }

        @Override
        public String call(String toolInput) {
            return session.invoke(definition.name(), delegate -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return session.invoke(definition.name(), delegate -> delegate.call(toolInput, toolContext));
        }
    }
}
