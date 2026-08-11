package com.agenttrail.web.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("dashscope")
public class DashScopeHealthIndicator implements HealthIndicator {

    private final String apiKey;

    public DashScopeHealthIndicator(@Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public Health health() {
        return apiKey == null || apiKey.isBlank()
                ? Health.down().withDetail("reason", "DashScope API key 未配置").build()
                : Health.up().withDetail("check", "仅检查 API key 配置存在").build();
    }
}
