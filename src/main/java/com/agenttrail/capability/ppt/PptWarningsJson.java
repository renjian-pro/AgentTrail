package com.agenttrail.capability.ppt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** warning 列表的稳定 JSON 编解码，供任务列表接口直接读取而不反序列化整个上下文。 */
final class PptWarningsJson {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<PptWarning>> TYPE = new TypeReference<>() { };

    private PptWarningsJson() {
    }

    static String toJson(List<PptWarning> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(warnings);
        } catch (Exception serializationFailed) {
            throw new PptGenerationException("序列化 PPT warning 失败: " + serializationFailed.getMessage(),
                    serializationFailed);
        }
    }

    static List<PptWarning> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, TYPE);
        } catch (Exception deserializationFailed) {
            throw new PptGenerationException("反序列化 PPT warning 失败: " + deserializationFailed.getMessage(),
                    deserializationFailed);
        }
    }
}
