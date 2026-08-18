package com.agenttrail.capability.ppt;

import java.nio.file.Path;

public interface RenderPort {
    void render(Path template, Path schemaFile, Path outputFile);

    /** 默认适配旧渲染端口；ProcessBuilder 实现会覆盖以便终止 Python 进程树。 */
    default void render(Path template, Path schemaFile, Path outputFile, PptCancellationToken cancellationToken) {
        cancellationToken.throwIfCancellationRequested();
        render(template, schemaFile, outputFile);
        cancellationToken.throwIfCancellationRequested();
    }
}
