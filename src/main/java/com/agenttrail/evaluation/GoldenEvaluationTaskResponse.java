package com.agenttrail.evaluation;

/** Pollable evaluation task response consumed by the admin evaluation view. */
public record GoldenEvaluationTaskResponse(String taskId, String status, int completedCases,
                                           int totalCases, GoldenTaskReport report, String error) {
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
}
