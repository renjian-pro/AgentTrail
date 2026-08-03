package com.agenttrail.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalConfigTest {

    @Test
    void loadsLocalYamlAsUtf8(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("application-local.yml");
        Files.writeString(file, "display:\n  name: 本地配置\n");

        assertThat(LocalConfig.load(file)).containsEntry("display.name", "本地配置");
    }

    @Test
    void convertsCanonicalPropertyNameToSpringEnvironmentName() {
        assertThat(LocalConfig.environmentName("agenttrail.ppt.image.api-key"))
                .isEqualTo("AGENTTRAIL_PPT_IMAGE_API_KEY");
    }

    @Test
    void missingLocalFileBehavesLikeAnEmptyConfiguration(@TempDir Path tempDir) {
        assertThat(LocalConfig.load(tempDir.resolve("missing.yml"))).isEmpty();
    }
}
