package com.agenttrail.loop.ppt;

/** Python 渲染子进程相关的失败（issue #24）：启动失败、超时、非零退出码、产物缺失。 */
public class PptRenderException extends RuntimeException {

    public PptRenderException(String message) {
        super(message);
    }

    public PptRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
