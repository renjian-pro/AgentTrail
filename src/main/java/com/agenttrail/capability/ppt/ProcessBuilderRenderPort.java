package com.agenttrail.capability.ppt;

import java.nio.file.Path;
import java.util.Objects;

public final class ProcessBuilderRenderPort implements RenderPort {
    private final PptPythonRenderer renderer;
    public ProcessBuilderRenderPort(PptPythonRenderer renderer) { this.renderer = Objects.requireNonNull(renderer); }
    @Override public void render(Path template, Path schemaFile, Path outputFile) {
        renderer.render(template.toString(), schemaFile, outputFile);
    }
}
