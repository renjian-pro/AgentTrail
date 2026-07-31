package com.agenttrail.loop.model;

/**
 * 一条待办任务。{@code content} 和 {@code activeForm} 服务两种不同的消费方——前者是模型
 * 自己规划时用的祈使形式，后者是执行中给前端展示的现在进行时形式——校验规则见
 * {@code TodoWriteTool}。
 *
 * @param content    任务描述，祈使形式（如"运行测试"）
 * @param activeForm 执行时展示的现在进行时形式（如"正在运行测试"）
 * @param status     当前状态
 */
public record TodoItem(String content, String activeForm, Status status) {

    public enum Status {
        PENDING, IN_PROGRESS, COMPLETED
    }
}
