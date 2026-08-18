package com.agenttrail.capability.ppt;

import java.util.List;

/**
 * PPT 意图识别（issue #24 骨架 + issue #32 补齐 MODIFY/RESUME 分支）——和需求澄清的"是否继续"
 * 判断一样（踩坑点 #52），不对用户消息做自然语言语义解析，只按固定关键词匹配，判定成本低、
 * 行为可预测、面试能讲清楚判断依据。
 *
 * <p><b>这里只有关键词一层，没有固定标记层。</b>此前这个类持有一对
 * {@code RESUME_MARKER}/{@code PAUSE_MARKER}（{@code 【开始生成PPT】}/{@code 【暂停生成PPT】}），
 * 类注释写着"由前端/调用方在用户明确点了继续生成/暂停生成按钮时拼进消息里"，并据此实现了
 * "标记优先、关键词兜底"两级判定。实际上前端从来没有发过这两个串——全库唯一引用它们的是本类
 * 自己的单测。真实的"继续"入口是卡片上那颗按钮，它直接打
 * {@code POST /agent/v1/ppt/resume/{taskId}}，带着精确的任务号绕过整个意图识别，比往消息里塞
 * 一个标记再解析出来可靠得多。留着那两个常量的代价不是多几行代码，是注释在描述一套并不存在的
 * 机制——照着它排查"为什么点了继续没生效"的人会先怀疑标记没拼对，而那条路径压根没跑过。
 *
 * <p>所以现在的判定就是它看起来的样子：{@link #RESUME_KEYWORDS} 命中即 RESUME，
 * {@link #MODIFY_KEYWORDS} 命中即 MODIFY，都不命中落到 {@link PptIntent#CREATE}。用户在对话里
 * 手打"继续生成"仍然有效，那是这层关键词一直在做的事。
 */
public final class PptIntentRecognizer {

    private static final List<String> RESUME_KEYWORDS = List.of("继续生成", "恢复生成", "接着生成", "继续之前的");
    private static final List<String> MODIFY_KEYWORDS = List.of("修改这个", "调整一下", "改一下第", "重新生成第");

    private PptIntentRecognizer() {
    }

    /** 默认落到 {@link PptIntent#CREATE}——没命中任何已知分支时，"新建一个"是唯一保证有明确处理路径的分支。 */
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
