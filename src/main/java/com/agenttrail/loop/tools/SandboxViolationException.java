package com.agenttrail.loop.tools;

/**
 * 路径越出目录白名单时抛出。
 *
 * <p>单独定义一个异常类型（而不是复用 {@link IllegalArgumentException}），是为了让上层能把
 * "越权访问"和"参数写错了"区分开——前者是安全事件，值得单独记日志/告警；后者只是模型手滑。
 */
public class SandboxViolationException extends IllegalArgumentException {

    public SandboxViolationException(String message) {
        super(message);
    }
}
