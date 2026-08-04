package com.agenttrail.capability.analytics.sql;

public record ValidationResult(boolean valid, String reason, String safeSql) {
    public static ValidationResult pass(String safeSql) {
        return new ValidationResult(true, "", safeSql);
    }

    public static ValidationResult reject(String reason) {
        return new ValidationResult(false, reason, null);
    }
}
