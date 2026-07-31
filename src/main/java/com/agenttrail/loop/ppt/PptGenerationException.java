package com.agenttrail.loop.ppt;

/** 状态机在某个状态执行失败时的统一异常（issue #24）——包一层，携带是哪个状态失败的语境。 */
public class PptGenerationException extends RuntimeException {

    public PptGenerationException(String message) {
        super(message);
    }

    public PptGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
