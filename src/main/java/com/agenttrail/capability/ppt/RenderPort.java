package com.agenttrail.capability.ppt;

import java.nio.file.Path;

public interface RenderPort {
    void render(Path template, Path schemaFile, Path outputFile);
}
