package com.agenttrail.web.dto;

import com.agenttrail.capability.deepresearch.DeepResearchReport;

/**
 * DeepResearch 异步入口的响应体——{@code status} 是 {@link #RUNNING}/{@link #SUCCESS}/
 * {@link #FAILED} 三态字符串，不是枚举：这张状态表只服务于"轮询有没有跑完"这一个用途，
 * 不像 {@link com.agenttrail.capability.ppt.PptState} 那样要驱动状态机本身的分发逻辑，没必要
 * 为它单开一个类型。
 *
 * @param report 只有 status 为 {@link #SUCCESS} 时非空
 * @param errorMsg 只有 status 为 {@link #FAILED} 时非空
 */
public record DeepResearchTaskResponse(long taskId, String status, DeepResearchReport report, String errorMsg,
        String currentStep) {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public static DeepResearchTaskResponse running(long taskId) {
        return running(taskId, "CLARIFYING");
    }

    public static DeepResearchTaskResponse running(long taskId, String currentStep) {
        return new DeepResearchTaskResponse(taskId, RUNNING, null, null, currentStep);
    }

    public static DeepResearchTaskResponse success(long taskId, DeepResearchReport report) {
        return success(taskId, report, null);
    }

    public static DeepResearchTaskResponse success(long taskId, DeepResearchReport report, String currentStep) {
        return new DeepResearchTaskResponse(taskId, SUCCESS, report, null, currentStep);
    }

    public static DeepResearchTaskResponse failed(long taskId, String errorMsg) {
        return failed(taskId, errorMsg, null);
    }

    public static DeepResearchTaskResponse failed(long taskId, String errorMsg, String currentStep) {
        return new DeepResearchTaskResponse(taskId, FAILED, null, errorMsg, currentStep);
    }

    public static DeepResearchTaskResponse cancelled(long taskId) {
        return cancelled(taskId, null);
    }

    public static DeepResearchTaskResponse cancelled(long taskId, String currentStep) {
        return new DeepResearchTaskResponse(taskId, CANCELLED, null, null, currentStep);
    }

    /** Terminal states keep the last observed step; only RUNNING tasks can be updated later. */
    public DeepResearchTaskResponse withCurrentStep(String currentStep) {
        return new DeepResearchTaskResponse(taskId, status, report, errorMsg, currentStep);
    }
}
