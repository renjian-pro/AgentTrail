package com.agenttrail.loop.ppt;

import java.util.List;

/**
 * PPT 意图识别（issue #24）——和需求澄清的"是否继续"判断一样（踩坑点 #52），不对用户消息做
 * 自然语言语义解析，只按固定关键词匹配，判定成本低、行为可预测、面试能讲清楚判断依据。
 *
 * <p>{@link PptIntent#RESUME}/{@link PptIntent#MODIFY} 关键词命中判定意图分类本身是识别出来了，
 * 但对应的处理分支不在这一票范围内（{@link PptGenerationService#create} 会直接拒绝），
 * 这里先把识别做完整，避免以后接上处理分支时还要回来重写识别逻辑。
 */
public final class PptIntentRecognizer {

    private static final List<String> RESUME_KEYWORDS = List.of("继续生成", "恢复生成", "接着生成", "继续之前的");
    private static final List<String> MODIFY_KEYWORDS = List.of("修改这个", "调整一下", "改一下第", "重新生成第");

    private PptIntentRecognizer() {
    }

    /** 默认落到 {@link PptIntent#CREATE}——没命中任何已知分支时，"新建一个"是唯一已实现、可以安全执行的分支。 */
    public static PptIntent recognize(String userMessage) {
        if (userMessage == null) {
            return PptIntent.CREATE;
        }
        if (RESUME_KEYWORDS.stream().anyMatch(userMessage::contains)) {
            return PptIntent.RESUME;
        }
        if (MODIFY_KEYWORDS.stream().anyMatch(userMessage::contains)) {
            return PptIntent.MODIFY;
        }
        return PptIntent.CREATE;
    }
}
