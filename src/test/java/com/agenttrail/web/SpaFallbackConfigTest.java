package com.agenttrail.web;

import com.agenttrail.web.SpaFallbackConfig.SpaIndexFallbackResourceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code createWebHistory()} 前端路由直接刷新/分享链接必须退回 index.html，但 {@code /agent/**}
 * 和 {@code /api/**} 写错路径必须继续 404，不能被这个兜底悄悄吞掉——这两条都是当初这个类
 * 存在的直接原因，缺一个都会退回错误行为。
 */
class SpaFallbackConfigTest {

    private final SpaIndexFallbackResourceResolver resolver = new SpaIndexFallbackResourceResolver();
    private final Resource staticRoot = new ClassPathResource("/static/");

    @Test
    void servesTheRealFileWhenItExists() throws Exception {
        Resource result = resolver.getResource("index.html", staticRoot);

        assertThat(result).isNotNull();
        assertThat(result.exists()).isTrue();
    }

    @Test
    void fallsBackToIndexHtmlForAPageShapedPathWithNoMatchingFile() throws Exception {
        Resource result = resolver.getResource("chat", staticRoot);

        assertThat(result).isNotNull();
        assertThat(result.getFilename()).isEqualTo("index.html");
    }

    @Test
    void doesNotFallBackForAMissingAgentApiPath() throws Exception {
        assertThat(resolver.getResource("agent/v1/does-not-exist", staticRoot)).isNull();
        assertThat(resolver.getResource("api/does-not-exist", staticRoot)).isNull();
    }
}
