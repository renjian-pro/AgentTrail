package com.agenttrail.loop.core;

/**
 * {@link AgentLoopExecutor#call} 以及恢复入口在建立事件流之前抛出的异常——调用方还没有可用的
 * 事件流承载错误，只能通过异常把 HTTP 404/409 等同步状态保留下来。
 *
 * @param code 和 {@link com.agenttrail.loop.model.AgentStreamEvent.Error#code()} 同一套取值，
 *             外加同步调用特有的 {@code "PAUSED"}（这一轮触发了暂停，同步调用不支持）
 */
public class AgentCallException extends RuntimeException {

    private final String code;

    public AgentCallException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
