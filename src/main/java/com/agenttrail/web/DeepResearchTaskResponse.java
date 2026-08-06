package com.agenttrail.web;

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
public record DeepResearchTaskResponse(long taskId, String status, DeepResearchReport report, String errorMsg) {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    static DeepResearchTaskResponse running(long taskId) {
        return new DeepResearchTaskResponse(taskId, RUNNING, null, null);
    }

    static DeepResearchTaskResponse success(long taskId, DeepResearchReport report) {
        return new DeepResearchTaskResponse(taskId, SUCCESS, report, null);
    }

    static DeepResearchTaskResponse failed(long taskId, String errorMsg) {
        return new DeepResearchTaskResponse(taskId, FAILED, null, errorMsg);
    }

    static DeepResearchTaskResponse cancelled(long taskId) {
        return new DeepResearchTaskResponse(taskId, CANCELLED, null, null);
    }
}
