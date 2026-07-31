package com.agenttrail.loop.tools.chart;

import com.agenttrail.loop.context.ContextPolicy;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 mcp-echarts + 真实 MinIO 跑一次真实图表生成（issue #23 验收标准，不接受 mock）：
 * 工具能被拿到、能被真的调用、返回的是一段可直接访问的图片 URL（不是 base64/二进制），
 * 且这张图片真的落进了配置好的 MinIO bucket——不能只信任 mcp-echarts 返回的 URL 字符串本身，
 * 要用 MinIO 官方 SDK 独立核实一遍。
 *
 * <p>依赖本机已经起了一个指向这台机器 MinIO 容器（{@code llmentor-minio}，
 * {@code minioadmin}/{@code minioadmin}，bucket {@code agenttrail-charts}）的 mcp-echarts
 * streamable-HTTP 实例：
 * <pre>
 * docker start llmentor-minio
 * MINIO_ENDPOINT=localhost MINIO_PORT=9000 MINIO_USE_SSL=false \
 * MINIO_ACCESS_KEY=minioadmin MINIO_SECRET_KEY=minioadmin MINIO_BUCKET_NAME=agenttrail-charts \
 * npx -y mcp-echarts -t streamable -p 3033
 * </pre>
 * bucket 需要提前建好并设置公开可读策略（mcp-echarts 自己不会建 bucket）。这不是这个测试类
 * 自己起的进程——和 {@code TavilySearchToolProviderIT} 依赖真实 Tavily 服务是同一个道理，
 * 本地没有起这个进程时这个测试会失败，不是这个类的 bug。
 */
class ChartToolProviderIT {

    private static final String MCP_URL = "http://localhost:3033/mcp";
    private static final String MINIO_ENDPOINT = "http://localhost:9000";
    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";
    private static final String MINIO_BUCKET = "agenttrail-charts";

    @Test
    void generatesARealChartAndUploadsItToMinioReturningAnAccessibleUrl() throws Exception {
        ChartToolProvider provider = new ChartToolProvider(MCP_URL, Duration.ofSeconds(20), 2);

        List<ToolCallback> tools = provider.toolCallbacks();
        assertThat(tools).as("真实 mcp-echarts 应该能拿到至少一个图表工具").isNotEmpty();
        assertThat(tools).allSatisfy(tool -> assertThat(tool).isInstanceOf(ChartToolCallback.class));

        ToolCallback barChart = tools.stream()
                .filter(tool -> tool.getToolDefinition().name().equals("generate_bar_chart"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到 generate_bar_chart 工具"));

        String result = barChart.call(
                "{\"title\":\"issue-23 IT 测试\",\"data\":"
                        + "[{\"category\":\"A\",\"value\":10},{\"category\":\"B\",\"value\":20}]}");

        assertThat(result).as("返回值必须是一段可直接访问的 URL，不能是 base64/二进制").startsWith("http");
        assertThat(result.length()).as("真正的 URL 应该很短——如果这里是几万字符，说明退化成了 base64")
                .isLessThan(300);

        URI chartUri = URI.create(result.trim());

        // 1) URL 本身要真的能访问到一张图片
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(chartUri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(contentType -> assertThat(contentType).startsWith("image/"));
        assertThat(response.body()).as("图片内容不应该是空的").isNotEmpty();

        // 2) 独立用 MinIO 官方 SDK 再验一次——不能只信任"URL 能访问"，图片必须是真的落在
        //    我们自己的 bucket 里，不是 mcp-echarts 顺手存到了别的什么地方还碰巧能访问到
        MinioClient minioClient = MinioClient.builder()
                .endpoint(MINIO_ENDPOINT)
                .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
                .build();
        String bucketPrefix = "/" + MINIO_BUCKET + "/";
        assertThat(chartUri.getPath()).as("URL 应该指向配置好的 agenttrail-charts bucket")
                .startsWith(bucketPrefix);
        String objectKey = chartUri.getPath().substring(bucketPrefix.length());
        assertThat(minioClient.statObject(StatObjectArgs.builder()
                        .bucket(MINIO_BUCKET)
                        .object(objectKey)
                        .build()))
                .as("MinIO 里必须真的存在这个对象").isNotNull();
    }

    @Test
    void toolCallbacksIsCachedAfterASuccessfulInitialization() {
        ChartToolProvider provider = new ChartToolProvider(MCP_URL, Duration.ofSeconds(20), 2);

        List<ToolCallback> first = provider.toolCallbacks();
        List<ToolCallback> second = provider.toolCallbacks();

        assertThat(second).as("第二次调用应该命中缓存，返回同一份工具列表").isSameAs(first);
    }

    /** issue #23 验收标准：图表工具的输出要能注册进 {@code ContextCompactor} 的保护名单。 */
    @Test
    void chartToolNamesFeedIntoContextPolicysProtectedToolsList() {
        ChartToolProvider provider = new ChartToolProvider(MCP_URL, Duration.ofSeconds(20), 2);

        Set<String> protectedNames = provider.protectedToolNames();
        assertThat(protectedNames).as("真实 mcp-echarts 暴露的每个图表工具名都应该能拿到")
                .isNotEmpty()
                .contains("generate_bar_chart", "generate_line_chart", "generate_pie_chart");

        ContextPolicy policy = ContextPolicy.builder()
                .protectedTools(protectedNames.toArray(String[]::new))
                .build();

        assertThat(protectedNames).allSatisfy(name -> assertThat(policy.isProtected(name)).isTrue());
        assertThat(policy.isProtected("executeSql")).as("没注册过的工具名不应该被误伤").isFalse();
    }
}
