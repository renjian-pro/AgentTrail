package com.agenttrail.loop.core;

/**
 * {@link AgentLoopExecutor#call} 抛出的异常——同步调用没有事件流可以把错误当一条
 * {@code AgentStreamEvent.Error} 推给调用方，只能转成异常。
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
