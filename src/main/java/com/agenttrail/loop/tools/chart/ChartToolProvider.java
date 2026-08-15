package com.agenttrail.loop.tools.chart;

import com.agenttrail.loop.tools.mcp.McpToolSession;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图表生成工具（mcp-echarts）的懒加载提供方（issue #23）——和
 * {@code websearch.TavilySearchToolProvider} 同一套模式：构造这个类本身不发一次网络请求，
 * 第一次真正有对话要用图表生成时才建连 MCP 客户端，建连本身带超时 + 有限次重试；
 * 只缓存*成功*的初始化结果，失败不缓存，下一次调用会重新尝试。这套"懒建连 + 只缓存成功 +
 * 会话死掉能自愈"的机制统一放在 {@link McpToolSession} 里，两个 provider 共用。
 *
 * <p><b>传输方式必须是 streamable-HTTP，不能是 stdio</b>：stdio 是"一个客户端进程独占一个
 * server 子进程"的模型，多个并发对话同时要画图时，stdio 管道会串话（这是参考实现真实踩过的坑，
 * 见 {@code docs/roadmap.md} Phase 5 和 {@code docs/architecture.md}）。streamable-HTTP 下
 * mcp-echarts 是一个独立部署、可被多个并发请求共用的 HTTP 服务，不存在这个问题。
 *
 * <p><b>MinIO 上传不需要在这里（Java 侧）重新实现一次</b>：mcp-echarts 自己内置了 MinIO 客户端
 * （{@code MINIO_ENDPOINT}/{@code MINIO_BUCKET_NAME} 等环境变量是那个 Node 进程自己的配置，
 * 不经过 Spring），渲染完图表后直接把图片传进配置好的 bucket，工具调用返回值本身就是一段
 * 可直接访问的 URL 纯文本——不是 base64、不是二进制。这一层只负责"以 streamable-HTTP 连上
 * 这个已经配好 MinIO 的 mcp-echarts 实例"，上传动作本身是那个外部进程的职责。
 * 注意那几个环境变量同时决定了**返回给浏览器的图片 URL**，所以不能填 compose 内部服务名，
 * 详见 {@code deploy/docker-compose.prod.yml} 里 mcp-echarts 那段注释。
 *
 * <p>mcp-echarts 一个端点会挂出十几个不同名字的工具（{@code generate_bar_chart}/
 * {@code generate_line_chart}/{@code generate_echarts} 等，每种图表类型一个），不是单个
 * 固定名字的工具——{@link #toolCallbacks()} 把它们原样全部包一层 {@link ChartToolCallback}
 * 返回，调用方要识别"这是不是图表工具"用 {@code instanceof}，不要假设只有一个工具名。
 */
public class ChartToolProvider {

    private final McpToolSession session;

    public ChartToolProvider(String mcpUrl, Duration timeout, int maxAttempts) {
        this.session = new McpToolSession("mcp-echarts", () -> connect(mcpUrl, timeout), maxAttempts);
    }

    /** 懒加载 + 只缓存成功结果；mcp-echarts 本次连不上时返回空列表，不抛异常。 */
    public List<ToolCallback> toolCallbacks() {
        return session.toolCallbacks().stream()
                .map(tool -> (ToolCallback) new ChartToolCallback(tool))
                .toList();
    }

    /**
     * 图表工具的名字集合，供装配层（{@code AgentLoopExecutorFactory}）传给
     * {@code ContextPolicy.builder().protectedTools(...)}，让 {@code ContextCompactor}
     * 永不压缩图表工具的返回内容——URL 本身很短，压缩没有意义，但更重要的是保持这套
     * "受保护工具名单"机制对未来任何新工具都是同一个套路，不用改 {@code ContextCompactor} 一行代码。
     *
     * <p>名字是从真实连上的 mcp-echarts 实例动态取的，不是硬编码在这——mcp-echarts 未来加新的
     * 图表类型工具时不需要改这里。本次不可用时返回空集合。
     */
    public Set<String> protectedToolNames() {
        return toolCallbacks().stream()
                .map(tool -> tool.getToolDefinition().name())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<ToolCallback> connect(String mcpUrl, Duration timeout) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(mcpUrl)
                .connectTimeout(timeout)
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
        client.initialize();

        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(List.of(client))
                .build();
        return List.of(provider.getToolCallbacks());
    }
}
