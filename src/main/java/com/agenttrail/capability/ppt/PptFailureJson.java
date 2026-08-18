package com.agenttrail.capability.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 单一职责的失败 JSON 编解码器，避免把 Jackson 细节扩散到两个 task store。 */
final class PptFailureJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PptFailureJson() {
    }

    static String toJson(PptFailure failure) {
        try {
            return JSON.writeValueAsString(failure);
        } catch (Exception serializationFailed) {
            throw new PptGenerationException("序列化 PPT 结构化失败失败: " + serializationFailed.getMessage(),
                    serializationFailed);
        }
    }

    static PptFailure fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, PptFailure.class);
        } catch (Exception deserializationFailed) {
            throw new PptGenerationException("反序列化 PPT 结构化失败失败: " + deserializationFailed.getMessage(),
                    deserializationFailed);
        }
    }
}
