package com.agenttrail.capability.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agenttrail.analytics.sql")
public class AnalyticsSqlProperties {
    private int maxJoins = 3;
    private int maxRows = 200;

    public int getMaxJoins() { return maxJoins; }
    public void setMaxJoins(int maxJoins) { this.maxJoins = maxJoins; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
}
