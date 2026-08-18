package com.agenttrail.capability.ppt;

/**
 * 持久化幂等创建的结果。{@code replay=true} 表示本次请求命中了数据库里已经存在的 taskId，
 * 调度层必须返回原任务而不是再次提交一个 worker。
 */
public record PptTaskCreation(long taskId, boolean replay) {
}
