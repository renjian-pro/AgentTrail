package com.agenttrail.capability.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** M-Schema 自省、缓存和刷新策略配置。 */
@ConfigurationProperties("agenttrail.analytics.schema")
public class AnalyticsSchemaProperties {
    private String provider = "mschema";
    private List<String> excludeTables = new ArrayList<>();
    private int sampleLimit = 5;
    private long sampleTimeoutMs = 5000;
    private long cacheTtlHours = 24;
    private String refreshCron = "0 0 3 * * *";

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public List<String> getExcludeTables() { return excludeTables; }
    public void setExcludeTables(List<String> excludeTables) { this.excludeTables = excludeTables == null ? new ArrayList<>() : new ArrayList<>(excludeTables); }
    public int getSampleLimit() { return sampleLimit; }
    public void setSampleLimit(int sampleLimit) { this.sampleLimit = sampleLimit; }
    public long getSampleTimeoutMs() { return sampleTimeoutMs; }
    public void setSampleTimeoutMs(long sampleTimeoutMs) { this.sampleTimeoutMs = sampleTimeoutMs; }
    public long getCacheTtlHours() { return cacheTtlHours; }
    public void setCacheTtlHours(long cacheTtlHours) { this.cacheTtlHours = cacheTtlHours; }
    public String getRefreshCron() { return refreshCron; }
    public void setRefreshCron(String refreshCron) { this.refreshCron = refreshCron; }
}
