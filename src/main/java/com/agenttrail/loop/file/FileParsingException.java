package com.agenttrail.loop.file;

/** Tika 解析失败时抛出——调用方（{@link FileQaService}）决定这算不算"工具结果"级别的错误。 */
public class FileParsingException extends RuntimeException {

    public FileParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
