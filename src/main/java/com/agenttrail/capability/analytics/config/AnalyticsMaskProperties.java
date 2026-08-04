package com.agenttrail.capability.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 采样和结果脱敏共享的字段名单，避免两条防线各维护一份配置。 */
@ConfigurationProperties("agenttrail.analytics")
public class
AnalyticsMaskProperties {
    private List<String> maskFields = new ArrayList<>();

    public List<String> getMaskFields() { return maskFields; }
    public void setMaskFields(List<String> maskFields) { this.maskFields = maskFields == null ? new ArrayList<>() : new ArrayList<>(maskFields); }
}
