package com.agenttrail.capability.ppt;

import java.util.List;

/**
 * PPT 意图识别（issue #24 骨架 + issue #32 补齐 MODIFY/RESUME 分支）——和需求澄清的"是否继续"
 * 判断一样（踩坑点 #52），不对用户消息做自然语言语义解析，只按固定标记/关键词匹配，判定成本低、
 * 行为可预测、面试能讲清楚判断依据。
 *
 * <p>{@link PptIntent#RESUME} 的判定是"固定标记优先，关键词兜底"两级结构，和
 * {@code DeepResearchService#needsMoreInfo}（{@code NEEDS_INFO_MARKER}/{@code READY_MARKER}）
 * 同一个坑同一套解法：{@link #RESUME_MARKER}（{@value #RESUME_MARKER}）/{@link #PAUSE_MARKER}
 * （{@value #PAUSE_MARKER}）由前端/调用方在用户明确点了"继续生成"/"暂停生成"按钮时拼进消息里，
 * 标记一旦出现就直接采信、不再看关键词；两个标记都没出现（用户就是在对话里随手打字）时才落到
 * {@link #RESUME_KEYWORDS} 这层兜底。{@link PptIntent#MODIFY} 目前只有关键词兜底，没有对应的
 * 固定标记——issue #32 的验收范围明确只要求"继续/暂停"这一组判断走标记，MODIFY 没有这个二义性
 * 问题（"改一下第 3 页"这类表达不存在"标记 vs 自然语言"的歧义场景）。
 */
public final class PptIntentRecognizer {

    /** 用户点击"继续生成"时，调用方拼进消息里的固定标记——命中即判定 {@link PptIntent#RESUME}，优先级高于关键词兜底。 */
    public static final String RESUME_MARKER = "【开始生成PPT】";

    /** 用户点击"暂停生成"时的固定标记——命中即判定"不是继续"，哪怕消息里恰好还夹带了继续类关键词
     * （例如复制粘贴历史提示文案），标记的优先级压过关键词兜底，不会被误判成 RESUME。 */
    public static final String PAUSE_MARKER = "【暂停生成PPT】";

    private static final List<String> RESUME_KEYWORDS = List.of("继续生成", "恢复生成", "接着生成", "继续之前的");
    private static final List<String> MODIFY_KEYWORDS = List.of("修改这个", "调整一下", "改一下第", "重新生成第");

    private PptIntentRecognizer() {
    }

    /** 默认落到 {@link PptIntent#CREATE}——没命中任何已知分支时，"新建一个"是唯一保证有明确处理路径的分支。 */
    public static PptIntent recognize(String userMessage) {
        if (userMessage == null) {
            return PptIntent.CREATE;
        }
        if (isResumeSignal(userMessage)) {
            return PptIntent.RESUME;
        }
        if (MODIFY_KEYWORDS.stream().anyMatch(userMessage::contains)) {
            return PptIntent.MODIFY;
        }
        return PptIntent.CREATE;
    }

    /**
     * 固定标记优先；{@link #RESUME_MARKER} 命中直接判定"继续"，{@link #PAUSE_MARKER} 命中直接判定
     * "不是继续"（两个标记都没出现才走关键词兜底）——和 {@code DeepResearchService#needsMoreInfo}
     * 的三段式判断结构完全对应，不做语义解析。
     */
    private static boolean isResumeSignal(String userMessage) {
        if (userMessage.contains(RESUME_MARKER)) {
            return true;
        }
        if (userMessage.contains(PAUSE_MARKER)) {
            return false;
        }
        return RESUME_KEYWORDS.stream().anyMatch(userMessage::contains);
    }
}
