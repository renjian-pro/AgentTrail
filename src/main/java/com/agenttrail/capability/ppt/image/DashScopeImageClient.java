package com.agenttrail.capability.ppt.image;

import com.agenttrail.capability.ppt.PptCancellationException;
import com.agenttrail.capability.ppt.PptCancellationToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;
import java.util.Map;

/**
 * DashScope {@code qwen-image-plus} 文生图 API 客户端（issue #31）——直接 REST 调用，不走 MCP，
 * 也不走 Spring AI 的 OpenAI 兼容模式（{@code spring.ai.openai.*} 那一层只封装了 chat/embedding，
 * 没有"输出是图片"这个 modality）。
 *
 * <p>接口契约核实自阿里云官方文档（{@code https://www.alibabacloud.com/help/en/model-studio/qwen-image-api}），
 * 不是凭经验假设：{@code POST /api/v1/services/aigc/multimodal-generation/generation}，
 * {@code qwen-image-plus} 既支持异步任务轮询也支持同步单次调用，这里用同步（不带
 * {@code X-DashScope-Async} 头），不引入轮询状态机的复杂度；返回体里的图片地址在
 * {@code output.choices[0].message.content[0].image}。文档原话是"图片 URL 在返回后 24 小时内
 * 有效"——这正是 issue #31 要解决的问题的第一手证据，不是这个类凭空假设时效性。
 */
public class DashScopeImageClient implements TextToImageClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final String size;
    private final Duration timeout;

    public DashScopeImageClient(String endpoint, String apiKey, String model, String size, Duration timeout) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.size = size;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String generateImageUrl(String prompt) {
        return generateImageUrl(prompt, PptCancellationToken.never());
    }

    @Override
    public String generateImageUrl(String prompt, PptCancellationToken cancellationToken) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new PptImageException("spring.ai.openai.api-key 未配置，无法调用文生图 API");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt), StandardCharsets.UTF_8))
                .build();

        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            while (true) {
                cancellationToken.throwIfCancellationRequested();
                try {
                    response = future.get(100, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException keepWaiting) {
                    // 每 100ms 检查一次取消令牌，避免长 HTTP 调用阻塞取消边界。
                }
            }
        } catch (PptCancellationException cancelled) {
            future.cancel(true);
            throw cancelled;
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PptImageException("调用 DashScope 文生图 API 被中断: " + endpoint, interrupted);
        } catch (ExecutionException callFailed) {
            throw new PptImageException("调用 DashScope 文生图 API 失败: " + endpoint, callFailed.getCause());
        }
        if (response.statusCode() != 200) {
            throw new PptImageException(
                    "DashScope 文生图 API 返回非 200 状态码 " + response.statusCode() + ": " + response.body());
        }
        return extractImageUrl(response.body());
    }

    /** 包内可见，方便单测不发真实网络请求就能校验请求体形状（issue #31：接口契约要能被测试锁定）。 */
    String buildRequestBody(String prompt) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", Map.of("messages", List.of(
                            Map.of("role", "user", "content", List.of(Map.of("text", prompt))))),
                    "parameters", Map.of("size", size, "n", 1, "watermark", false));
            return MAPPER.writeValueAsString(body);
        } catch (Exception serializeFailed) {
            throw new PptImageException("构造 DashScope 文生图请求体失败", serializeFailed);
        }
    }

    /** 包内可见，方便单测用一段手写的响应 JSON 锁定解析路径，不依赖真实网络请求。 */
    String extractImageUrl(String responseBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (IOException malformed) {
            throw new PptImageException(
                    "解析 DashScope 文生图 API 返回体失败，不是合法 JSON: " + responseBody, malformed);
        }
        JsonNode imageNode = root.path("output").path("choices").path(0)
                .path("message").path("content").path(0).path("image");
        if (imageNode.isMissingNode() || !imageNode.isTextual() || imageNode.asText().isBlank()) {
            throw new PptImageException("DashScope 文生图 API 返回体里没有找到 image 字段: " + responseBody);
        }
        return imageNode.asText();
    }
}
