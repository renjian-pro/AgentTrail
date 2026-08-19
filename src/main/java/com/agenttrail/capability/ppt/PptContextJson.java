package com.agenttrail.capability.ppt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link PptGenerationContext} 与 JSON 之间的双向转换，{@link PptTaskStore} 实现落地/读回整份
 * 上下文快照要用。
 *
 * <p>和 {@code PauseStateJson} 不一样，这里不需要手工在领域对象和 Map/List 之间转换——
 * {@link PptGenerationContext} 全部由字符串/整数/列表/无多态的嵌套 record 组成，没有
 * {@code Message} 那种需要按具体子类型重建的多态字段，也没有 {@code Class<?>} 这种 Jackson
 * 不认识的类型，直接对着 record 序列化/反序列化即可（Java record 的 component 名字始终能通过
 * 反射拿到，不依赖编译时 {@code -parameters} 选项，Jackson 2.12+ 原生支持）。
 */
final class PptContextJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PptContextJson() {
    }

    static String toJson(PptGenerationContext context) {
        try {
            return JSON.writeValueAsString(context);
        } catch (Exception serializationFailed) {
            throw new PptGenerationException(
                    "序列化 PPT 生成上下文失败: " + serializationFailed.getMessage(), serializationFailed);
        }
    }

    static PptGenerationContext fromJson(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) {
                throw new PptContextMigrationException("PPT_CONTEXT_MIGRATION_FAILED", "上下文快照必须是 JSON 对象");
            }
            // 缺失版本的历史快照按 0 读取，然后在这里一次性迁移到当前版本，避免每个
            // Strategy 都各自猜测旧字段。未来版本明确拒绝，任务会留在可诊断的迁移错误上。
            int persistedVersion = root.has("contextVersion") && !root.get("contextVersion").isNull()
                    ? root.get("contextVersion").asInt(-1) : 0;
            if (persistedVersion < 0 || persistedVersion > PptGenerationContext.CURRENT_CONTEXT_VERSION) {
                throw new PptContextMigrationException("PPT_CONTEXT_MIGRATION_FAILED",
                        "不支持的 PPT 上下文版本: " + persistedVersion);
            }
            PptGenerationContext parsed = JSON.treeToValue(root, PptGenerationContext.class);
            return parsed.contextVersion() == PptGenerationContext.CURRENT_CONTEXT_VERSION
                    ? parsed
                    : new PptGenerationContext(parsed.conversationId(), parsed.userRequirement(), parsed.requirement(),
                            parsed.searchMaterials(), parsed.templatePath(), parsed.outline(), parsed.schema(),
                            parsed.outputPath(), parsed.clarifyingQuestion(),
                            PptGenerationContext.CURRENT_CONTEXT_VERSION, parsed.warnings(), parsed.visualPlan(),
                            parsed.assetTasks(), parsed.templateRef(), parsed.artifactRef(), parsed.operation(),
                            parsed.baseTaskId(), parsed.baseArtifactId());
        } catch (PptContextMigrationException migrationFailed) {
            throw migrationFailed;
        } catch (Exception deserializationFailed) {
            throw new PptContextMigrationException("PPT_CONTEXT_MIGRATION_FAILED",
                    "反序列化 PPT 生成上下文失败: " + deserializationFailed.getMessage(), deserializationFailed);
        }
    }
}
