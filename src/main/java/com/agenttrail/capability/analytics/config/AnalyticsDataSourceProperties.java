package com.agenttrail.capability.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 分析只读连接池配置；账号与主库可以相同，但生产必须使用只读业务账号。 */
@ConfigurationProperties("agenttrail.analytics.datasource")
public class AnalyticsDataSourceProperties {

    private boolean enabled;
    private String jdbcUrl = "";
    private String username = "";
    private String password = "";
    private int maximumPoolSize = 10;
    private long connectionTimeoutMs = 5000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getMaximumPoolSize() { return maximumPoolSize; }
    public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
    public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
    public void setConnectionTimeoutMs(long connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }
}
