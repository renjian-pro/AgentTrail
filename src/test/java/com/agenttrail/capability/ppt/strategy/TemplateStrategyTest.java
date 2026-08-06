package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateStrategyTest {

    @Test
    void resolvesAnExistingTemplateFileIntoTheContext() throws Exception {
        Path template = Files.createTempFile("template", ".pptx");
        try {
            TemplateStrategy strategy = new TemplateStrategy(template.toString());

            PptGenerationContext result = strategy.execute(PptGenerationContext.initial("conv-1", "问题"));

            assertThat(result.templatePath()).isEqualTo(template.toString());
        } finally {
            Files.deleteIfExists(template);
        }
    }

    @Test
    void rejectsAMisconfiguredTemplatePathEarlyInsteadOfFailingAtRenderTime(@TempDir Path tempDir) {
        String missingPath = tempDir.resolve("does-not-exist.pptx").toString();
        TemplateStrategy strategy = new TemplateStrategy(missingPath);

        assertThatThrownBy(() -> strategy.execute(PptGenerationContext.initial("conv-1", "问题")))
                .isInstanceOf(PptGenerationException.class)
                .hasMessageContaining(missingPath);
    }
}
