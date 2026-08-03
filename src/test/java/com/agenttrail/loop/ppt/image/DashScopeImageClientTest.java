package com.agenttrail.loop.ppt.image;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DashScopeImageClient} 的确定性单测（issue #31）——不打真实 DashScope，用本地
 * {@link HttpServer} 起一个 stub，脚本化返回体，锁定"请求怎么发、返回体怎么解析"这条契约：
 * 真实文档核实的接口形状见类注释——{@code output.choices[0].message.content[0].image}，
 * 这里用手写的响应体固定住这条解析路径，不依赖真实网络请求（真实网络请求见 issue #31 的
 * IT 测试，需要本地配置真实的 {@code spring.ai.openai.api-key}）。
 */
class DashScopeImageClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesTheImageUrlOutOfARealisticDashScopeResponseBody() throws IOException {
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server = stubServer(200, """
                {
                  "output": {
                    "choices": [
                      {
                        "finish_reason": "stop",
                        "message": {
                          "content": [
                            {"image": "https://dashscope-result.oss-cn-beijing.aliyuncs.com/abc.png?Expires=123"}
                          ],
                          "role": "assistant"
                        }
                      }
                    ]
                  },
                  "usage": {"height": 1024, "image_count": 1, "width": 1024},
                  "request_id": "req-1"
                }
                """, exchange -> {
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        });

        DashScopeImageClient client = new DashScopeImageClient(
                "http://localhost:" + server.getAddress().getPort() + "/generation",
                "sk-test-key", "qwen-image-plus", "1024*1024", Duration.ofSeconds(5));

        String imageUrl = client.generateImageUrl("一只猫");

        assertThat(imageUrl).isEqualTo("https://dashscope-result.oss-cn-beijing.aliyuncs.com/abc.png?Expires=123");
        assertThat(receivedAuthHeader.get()).isEqualTo("Bearer sk-test-key");
        assertThat(receivedBody.get()).contains("qwen-image-plus").contains("一只猫").contains("1024*1024");
    }

    @Test
    void throwsWhenApiKeyIsBlankWithoutMakingAnyNetworkCall() {
        DashScopeImageClient client = new DashScopeImageClient(
                "http://localhost:1/unused", "", "qwen-image-plus", "1024*1024", Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.generateImageUrl("一只猫"))
                .isInstanceOf(PptImageException.class)
                .hasMessageContaining("spring.ai.openai.api-key");
    }

    @Test
    void throwsWithResponseBodyWhenTheApiReturnsANonOkStatus() throws IOException {
        server = stubServer(500, "{\"code\":\"InternalError\",\"message\":\"boom\"}", exchange -> {
        });

        DashScopeImageClient client = new DashScopeImageClient(
                "http://localhost:" + server.getAddress().getPort() + "/generation",
                "sk-test-key", "qwen-image-plus", "1024*1024", Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.generateImageUrl("一只猫"))
                .isInstanceOf(PptImageException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("boom");
    }

    @Test
    void throwsWhenTheResponseBodyHasNoImageField() throws IOException {
        server = stubServer(200, "{\"output\":{\"choices\":[]}}", exchange -> {
        });

        DashScopeImageClient client = new DashScopeImageClient(
                "http://localhost:" + server.getAddress().getPort() + "/generation",
                "sk-test-key", "qwen-image-plus", "1024*1024", Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.generateImageUrl("一只猫"))
                .isInstanceOf(PptImageException.class)
                .hasMessageContaining("image");
    }

    private interface RequestObserver {
        void observe(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private static HttpServer stubServer(int statusCode, String responseBody, RequestObserver observer)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/generation", exchange -> {
            observer.observe(exchange);
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
