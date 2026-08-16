package com.agenttrail.conversation.digest;

import com.agenttrail.web.dto.ConversationTurnResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #103。之前用户说"根据前面的会话帮我生成 PPT"，任务只收到那一句话本身，产出一份
 * 题为"前面的会话"的幻灯片，而且不报错。
 */
class ConversationDigestTest {

    private static ConversationTurnResponse turn(String question, String answer) {
        return new ConversationTurnResponse(1L, question, answer, null, null, 0L);
    }

    /** 空会话返回 empty 而不是空串：调用方要能区分"没有上下文"和"上下文是空的"。 */
    @Test
    void reportsNoContextForAnEmptyConversation() {
        assertThat(ConversationDigest.render(List.of())).isEmpty();
        assertThat(ConversationDigest.render(null)).isEmpty();
        assertThat(ConversationDigest.render(List.of(turn("  ", null)))).isEmpty();
    }

    @Test
    void rendersQuestionsAndAnswersAsPlainText() {
        var digest = ConversationDigest.render(List.of(
                turn("各门店营收排名是怎样的", "门店 A 最高"),
                turn("那上个月呢", "还是门店 A")));

        assertThat(digest).get().asString()
                .contains("用户：各门店营收排名是怎样的")
                .contains("助手：门店 A 最高")
                .contains("用户：那上个月呢");
    }

    /** 只取最近若干轮——更早的内容对"接着刚才的话题做点什么"几乎没贡献，白占任务链路的预算。 */
    @Test
    void keepsOnlyTheMostRecentTurns() {
        List<ConversationTurnResponse> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> turn("问题" + i, "回答" + i)).toList();

        String digest = ConversationDigest.render(many).orElseThrow();

        assertThat(digest).contains("问题19").doesNotContain("问题0\n");
    }

    /**
     * 摘要必须标注成"之前对话的摘要（供参考，不是本次要求）"。不加这层标注，模型会把摘要里的
     * 历史提问当成本次任务的指令去执行。
     */
    @Test
    void labelsTheDigestSoItIsNotMistakenForThisTasksInstruction() {
        String prefixed = ConversationDigest.asTaskPrefix("用户：营收排名\n助手：门店 A", "做成 PPT");

        assertThat(prefixed)
                .contains("之前对话的摘要").contains("不是本次要求")
                .contains("# 本次要求").contains("做成 PPT");
        assertThat(prefixed.indexOf("之前对话的摘要")).isLessThan(prefixed.indexOf("# 本次要求"));
    }

    @Test
    void flagsOverlongDigestsForCompression() {
        assertThat(ConversationDigest.needsCompression("短")).isFalse();
        assertThat(ConversationDigest.needsCompression("x".repeat(3000))).isTrue();
    }
}
