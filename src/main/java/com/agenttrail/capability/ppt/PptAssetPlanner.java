package com.agenttrail.capability.ppt;

import java.util.ArrayList;
import java.util.List;

/**
 * 从业务 Schema 派生逐页素材任务。它只规划，不发起网络调用；因此可以在 ASSET 阶段 checkpoint
 * 之前重复执行而不产生对象。执行者拿到的 prompt/visualPlan 已固定，不能在每页重新猜风格。
 */
public final class PptAssetPlanner {
    private PptAssetPlanner() {
    }

    public static List<PptAssetTask> plan(PptSchema schema, PptVisualPlan visualPlan) {
        if (schema == null) throw new PptGenerationException("PPT Schema 不能为空");
        List<PptAssetTask> result = new ArrayList<>();
        if (!schema.pages().isEmpty()) {
            for (PptPage page : schema.pages()) {
                for (var entry : page.fields().entrySet()) {
                    if (entry.getValue().type() == PptFieldType.IMAGE) {
                        String prompt = promptFor(page.pageId(), entry.getKey(), entry.getValue());
                        result.add(PptAssetTask.planned(page.pageId(), entry.getKey(), PptFieldType.IMAGE,
                                prompt, visualPlan, PptAssetKey.digest(prompt)));
                    }
                }
            }
            return List.copyOf(result);
        }
        if (schema.coverImageUrl() == null) {
            String prompt = "为 PPT 封面生成一张与主题一致且不含文字的配图";
            result.add(PptAssetTask.planned("cover", "coverImage", PptFieldType.IMAGE, prompt,
                    visualPlan, PptAssetKey.digest(prompt)));
        }
        return List.copyOf(result);
    }

    private static String promptFor(String pageId, String fieldName, PptField field) {
        String hint = field.text() != null ? field.text() : String.valueOf(field.value());
        return "为页面 " + pageId + " 的字段 " + fieldName + " 生成配图，内容提示：" + hint;
    }
}
