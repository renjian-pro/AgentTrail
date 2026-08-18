package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.capability.ppt.PptGenerationContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLARIFY 状态的判定语义——和 {@code DeepResearchServiceTest} 里对 {@code needsMoreInfo} 的用例
 * 一一对应：固定标记优先、关键词兜底、都不命中时默认放行。
 */
class ClarifyStrategyTest {

    private static ClarifyStrategy strategyReturning(String verdict) {
        return new ClarifyStrategy(new AgentLoopExecutor(new ScriptedChatModel(List.of(text(verdict))), List.of(), 3));
    }

    @Test
    void writesTheQuestionIntoTheContextWhenTheModelSaysInformationIsMissing() {
        ClarifyStrategy strategy = strategyReturning("【需要补充信息】这份 PPT 想讲什么主题？给谁看？");

        PptGenerationContext result = strategy.execute(PptGenerationContext.initial("conv-1", "什么情况"));

        // 标记本身不该留在给用户看的追问里
        assertThat(result.clarifyingQuestion()).isEqualTo("这份 PPT 想讲什么主题？给谁看？");
    }

    @Test
    void leavesTheContextUntouchedWhenTheModelSaysItCanStart() {
        ClarifyStrategy strategy = strategyReturning("【开始生成】围绕 Spring AI Agent 的实战经验展开。");

        PptGenerationContext result = strategy.execute(PptGenerationContext.initial("conv-1", "做一份 Spring AI 分享"));

        assertThat(result.clarifyingQuestion()).isNull();
    }

    /**
     * 标记优先级压过关键词：模型既打了"可以开始"的标记、正文里又恰好出现"请补充"这类词时，
     * 以标记为准。否则一段措辞里带了兜底关键词的正常判定会被误判成追问，把任务卡在等人。
     */
    @Test
    void treatsTheReadyMarkerAsAuthoritativeEvenWhenFallbackKeywordsAppear() {
        ClarifyStrategy strategy = strategyReturning("【开始生成】方向已明确，无需请补充其它信息。");

        assertThat(strategy.execute(PptGenerationContext.initial("conv-1", "做一份年终总结")).clarifyingQuestion())
                .isNull();
    }

    @Test
    void fallsBackToKeywordsWhenNeitherMarkerIsPresent() {
        ClarifyStrategy strategy = strategyReturning("请问您想做哪个方面的 PPT？");

        assertThat(strategy.execute(PptGenerationContext.initial("conv-1", "嗯")).clarifyingQuestion())
                .isEqualTo("请问您想做哪个方面的 PPT？");
    }

    /** 既没标记也没关键词时默认放行——宁可少追问也不要反复打断，和澄清提示词正文的倾向一致。 */
    @Test
    void defaultsToSufficientWhenTheModelOutputMatchesNothing() {
        ClarifyStrategy strategy = strategyReturning("这是一份关于季度复盘的演示。");

        assertThat(strategy.execute(PptGenerationContext.initial("conv-1", "季度复盘")).clarifyingQuestion())
                .isNull();
    }
}
