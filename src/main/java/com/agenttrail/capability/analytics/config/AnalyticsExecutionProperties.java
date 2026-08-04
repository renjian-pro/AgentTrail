package com.agenttrail.capability.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agenttrail.analytics.execution")
public class AnalyticsExecutionProperties {
    private int explainTimeoutSeconds = 3;
    private long maxEstimatedRows = 100000;
    private int queryTimeoutSeconds = 10;
    private int maxRows = 200;
    private int previewRows = 20;
    private int transientRetries = 2;

    public int getExplainTimeoutSeconds() { return explainTimeoutSeconds; }
    public void setExplainTimeoutSeconds(int value) { explainTimeoutSeconds = value; }
    public long getMaxEstimatedRows() { return maxEstimatedRows; }
    public void setMaxEstimatedRows(long value) { maxEstimatedRows = value; }
    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
    public void setQueryTimeoutSeconds(int value) { queryTimeoutSeconds = value; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int value) { maxRows = value; }
    public int getPreviewRows() { return previewRows; }
    public void setPreviewRows(int value) { previewRows = value; }
    public int getTransientRetries() { return transientRetries; }
    public void setTransientRetries(int value) { transientRetries = value; }
}
