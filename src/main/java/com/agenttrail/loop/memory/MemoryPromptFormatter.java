package com.agenttrail.loop.memory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 把记忆条目按类型分组，格式化成注入 prompt 用的稳定文本块（issue #19）：
 *
 * <pre>
 * # 长期记忆
 *
 * ## 用户画像
 * - 产品经理，擅长数据分析
 *
 * ## 用户偏好
 * - 偏好中文回复
 * </pre>
 */
public final class MemoryPromptFormatter {

    private static final Map<MemoryType, String> TYPE_LABELS = Map.of(
            MemoryType.PROFILE, "用户画像",
            MemoryType.PREFERENCE, "用户偏好",
            MemoryType.INSTRUCTION, "行为要求",
            MemoryType.FACT, "已知事实");

    private MemoryPromptFormatter() {
    }

    /** @return 记忆为空时返回空字符串，供调用方直接判断"要不要插入这个区块"。 */
    public static String formatSection(List<MemoryItem> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        EnumMap<MemoryType, List<String>> grouped = new EnumMap<>(MemoryType.class);
        for (MemoryItem item : memories) {
            grouped.computeIfAbsent(item.type(), ignored -> new ArrayList<>()).add(item.content());
        }

        StringBuilder section = new StringBuilder("# 长期记忆\n");
        for (MemoryType type : MemoryType.values()) {
            List<String> contents = grouped.get(type);
            if (contents == null || contents.isEmpty()) {
                continue;
            }
            section.append("\n## ").append(TYPE_LABELS.get(type)).append('\n');
            for (String content : contents) {
                section.append("- ").append(content).append('\n');
            }
        }
        return section.toString();
    }
}
