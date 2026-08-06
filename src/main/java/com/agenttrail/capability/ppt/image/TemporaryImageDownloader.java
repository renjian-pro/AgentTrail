package com.agenttrail.capability.ppt.image;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 下载文生图 API 返回的临时图片 URL（issue #31）——从 {@link MinioPptImageStore} 里独立抽出这
 * 一小段，是为了能用本地 stub HTTP server 写确定性单测覆盖"下载失败要老实抛异常，不能静默吞掉"
 * 这条路径：真实的 DashScope 临时链接本身是一次性/有效期内才能访问的，没法反复用在单测里，
 * 单测只需要证明"这一小段下载逻辑对各种失败状态码/空内容的反应是对的"，不需要每次都打真实网络。
 */
final class TemporaryImageDownloader {

    private TemporaryImageDownloader() {
    }

    record DownloadedImage(byte[] bytes, String contentType) {
    }

    static DownloadedImage download(HttpClient httpClient, String url, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException downloadFailed) {
            throw new PptImageException("下载文生图临时链接失败: " + url, downloadFailed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PptImageException("下载文生图临时链接被中断: " + url, interrupted);
        }
        if (response.statusCode() != 200) {
            throw new PptImageException("下载文生图临时链接失败，HTTP " + response.statusCode() + ": " + url);
        }
        if (response.body() == null || response.body().length == 0) {
            throw new PptImageException("下载文生图临时链接返回了空内容: " + url);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("image/png");
        return new DownloadedImage(response.body(), contentType);
    }
}
