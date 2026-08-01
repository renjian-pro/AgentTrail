package com.agenttrail.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecretsTest {

    @Test
    void loadsLocalPropertiesAsUtf8(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("secrets.properties");
        Files.writeString(file, "DISPLAY_NAME=本地配置\n");

        assertThat(Secrets.load(file)).containsEntry("DISPLAY_NAME", "本地配置");
    }

    @Test
    void missingLocalFileBehavesLikeAnEmptyConfiguration(@TempDir Path tempDir) {
        assertThat(Secrets.load(tempDir.resolve("missing.properties"))).isEmpty();
    }
}
