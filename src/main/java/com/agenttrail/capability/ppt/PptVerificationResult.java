package com.agenttrail.capability.ppt;

import java.util.List;

/** VERIFY 的确定性结果；硬失败阻止 SUCCESS，warning 只追加到上下文。 */
public record PptVerificationResult(String checksum, long sizeBytes, int slideCount, List<String> warnings) {
    public PptVerificationResult {
        if (checksum == null || checksum.isBlank() || sizeBytes <= 0 || slideCount <= 0) {
            throw new IllegalArgumentException("PPT verification result is incomplete");
        }
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
