package com.agenttrail.loop.ppt.image;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 下载文生图 API 临时链接这一小段逻辑的确定性单测（issue #31）——用本地 {@link HttpServer} 脚本化
 * 各种失败状态，证明"下载失败要老实抛 {@link PptImageException}，不能静默返回 null/空字节"这条
 * 约束，不依赖真实的、有时效性的 DashScope 临时链接。
 */
class TemporaryImageDownloaderTest {

    private HttpServer server;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downloadsTheBytesAndContentTypeOnASuccessfulResponse() throws IOException {
        byte[] fakeImageBytes = {1, 2, 3, 4, 5};
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/image.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, fakeImageBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(fakeImageBytes);
            }
        });
        server.start();

        var result = TemporaryImageDownloader.download(
                httpClient, "http://localhost:" + server.getAddress().getPort() + "/image.png",
                Duration.ofSeconds(5));

        assertThat(result.bytes()).isEqualTo(fakeImageBytes);
        assertThat(result.contentType()).isEqualTo("image/png");
    }

    @Test
    void throwsWhenTheServerReturnsANonOkStatus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/expired.png", exchange -> {
            exchange.sendResponseHeaders(403, -1);
        });
        server.start();

        String url = "http://localhost:" + server.getAddress().getPort() + "/expired.png";
        assertThatThrownBy(() -> TemporaryImageDownloader.download(httpClient, url, Duration.ofSeconds(5)))
                .isInstanceOf(PptImageException.class)
                .hasMessageContaining("403");
    }

    @Test
    void throwsWhenTheServerReturnsAnEmptyBody() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/empty.png", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();

        String url = "http://localhost:" + server.getAddress().getPort() + "/empty.png";
        assertThatThrownBy(() -> TemporaryImageDownloader.download(httpClient, url, Duration.ofSeconds(5)))
                .isInstanceOf(PptImageException.class)
                .hasMessageContaining("空内容");
    }

    @Test
    void throwsWhenTheHostIsUnreachable() {
        assertThatThrownBy(() -> TemporaryImageDownloader.download(
                httpClient, "http://127.0.0.1:1/unreachable.png", Duration.ofSeconds(2)))
                .isInstanceOf(PptImageException.class);
    }
}
