package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptArtifactId;
import com.agenttrail.capability.ppt.PptArtifactRef;
import com.agenttrail.capability.ppt.PptArtifactStore;
import com.agenttrail.capability.ppt.PptArtifactUpload;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationException;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptVerifier;
import com.agenttrail.capability.ppt.PptVerificationResult;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VERIFY 状态：先验证本地 PPTX，再以稳定 artifact key 上传。上传成功前状态机不能推进 SUCCESS；
 * 签名下载 URL 永远只在 API 查询时生成，不写入上下文或会话历史。
 */
public final class VerifyStrategy implements PptGenerationStrategy {
    private static final Logger log = LoggerFactory.getLogger(VerifyStrategy.class);
    private static final String PPT_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private final PptVerifier verifier;
    private final PptArtifactStore artifactStore;

    public VerifyStrategy(PptArtifactStore artifactStore) {
        this(new PptVerifier(), artifactStore);
    }

    public VerifyStrategy(PptVerifier verifier, PptArtifactStore artifactStore) {
        this.verifier = verifier;
        this.artifactStore = artifactStore;
    }

    @Override
    public PptState handledState() {
        return PptState.VERIFY;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        if (context.outputPath() == null || context.outputPath().isBlank()) {
            throw new PptGenerationException("VERIFY 缺少渲染产物路径");
        }
        Path output = Path.of(context.outputPath()).toAbsolutePath().normalize();
        long startedAt = System.nanoTime();
        PptVerificationResult result = verifier.verify(output, context.schema());
        String owner = context.conversationId() == null ? "legacy" : context.conversationId();
        String objectKey = PptArtifactId.objectKey(owner, 0, output);
        var artifact = artifactStore.put(new PptArtifactUpload(owner, 0, output, PPT_CONTENT_TYPE, objectKey));
        if (!artifact.checksum().equals(result.checksum()) || artifact.sizeBytes() != result.sizeBytes()) {
            throw new PptGenerationException("PPT 产物上传校验失败");
        }
        log.info("PPT verify completed conversationId={} pageCount={} sizeBytes={} checksumMatched=true durationMs={}",
                context.conversationId(), result.slideCount(), result.sizeBytes(),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        return context.withArtifactRef(PptArtifactRef.from(artifact));
    }
}
