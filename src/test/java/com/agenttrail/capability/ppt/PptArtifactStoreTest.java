package com.agenttrail.capability.ppt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证产物上传使用稳定 artifactId/key，并且下载 URL 不进入任务上下文。 */
class PptArtifactStoreTest {

    @Test
    void reusesTheSameObjectForTheSameRenderedBytes() throws Exception {
        Path root = Files.createTempDirectory("ppt-artifacts");
        Path rendered = Files.createTempFile("rendered", ".pptx");
        Files.writeString(rendered, "deterministic-pptx-bytes");
        LocalPptArtifactStore store = new LocalPptArtifactStore(root);
        PptArtifactUpload upload = new PptArtifactUpload("user-1", 7, rendered,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                PptArtifactId.objectKey("user-1", 7, rendered));

        PptArtifact first = store.put(upload);
        PptArtifact second = store.put(upload);

        assertThat(second.artifactId()).isEqualTo(first.artifactId());
        assertThat(second.objectKey()).isEqualTo(first.objectKey());
        assertThat(store.signedDownloadUrl(first.artifactId(), java.time.Duration.ofMinutes(1)))
                .startsWith("file:");
    }

    @Test
    void reopensAnOoxmlFileAndChecksExpectedSlideCountBeforeUpload() {
        Path template = Path.of("src/main/resources/ppt-templates/default-template.pptx").toAbsolutePath().normalize();
        PptSchema schema = new PptSchema("标题", "副标题",
                java.util.List.of(new PptContentSlideFill("页标题", "页正文")));

        PptVerificationResult result = new PptVerifier().verify(template, schema);

        assertThat(result.slideCount()).isEqualTo(2);
        assertThat(result.sizeBytes()).isGreaterThan(0);
    }
}
