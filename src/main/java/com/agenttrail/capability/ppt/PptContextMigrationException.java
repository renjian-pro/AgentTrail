package com.agenttrail.capability.ppt;

/**
 * 上下文快照无法从持久化版本迁移时使用的稳定异常。
 * code 不包含 Jackson 具体错误，便于任务表、日志和前端按协议识别迁移失败。
 */
public final class PptContextMigrationException extends PptGenerationException {

    private final String code;

    public PptContextMigrationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PptContextMigrationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
