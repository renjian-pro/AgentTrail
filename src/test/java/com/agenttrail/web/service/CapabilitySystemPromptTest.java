package com.agenttrail.web.service;

import com.agenttrail.loop.prompt.PromptRegistry;
import com.agenttrail.loop.security.DataProvenancePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #111 / R22：每个能力模式都要带上自己的角色 / 边界提示词。
 *
 * <h2>这组用例守的是什么</h2>
 *
 * 改造前 {@code ContextAssembler.assemble(null, ...)} 的 systemPrompt 参数**恒为 null**，四个模式
 * 共用一套空骨架——系统消息里只有日期 / 记忆 / 文件三个与模式无关的区块，没有任何关于"我是谁、
 * 我的边界在哪、该怎么用这些工具"的指令。DataAgent 的全部行为约束都寄托在 Skill 的 SOP 上，
 * 而 Skill 由模型自己决定调不调（R2-risk）。
 *
 * <p>requirements.md §7.5 那条实测到的失败正是这个空缺的产物：**模型有图表工具、没有 SQL 结果，
 * 就编数据画图**。工具集这道硬边界防住了"调不到 SQL"，防不住"拿图表工具画假数据"。
 */
class CapabilitySystemPromptTest {

    private static final PromptRegistry PROMPTS = PromptRegistry.loadFromClasspath();

    @Test
    @DisplayName("普通对话和数据分析各自绑定一份模式级提示词")
    void bindsOneSystemPromptPerUserFacingMode() {
        assertThat(CapabilitySpec.chat(List.of(), List.of(), DataProvenancePolicy.DISABLED).systemPromptId())
                .isEqualTo("chat.system");
        assertThat(CapabilitySpec.analytics(List.of(), List.of(), DataProvenancePolicy.DISABLED).systemPromptId())
                .isEqualTo("analytics.system");
    }

    /**
     * 内部编排子调用（DeepResearch 的 critique/plan/summarize）各自已经带着 `prompts/deepresearch/*`
     * 里那份很具体的任务提示词，上面再压一层"你是 AgentTrail 的通用助手"只会互相打架。
     */
    @Test
    @DisplayName("内部编排子调用不挂模式级提示词")
    void leavesInternalOrchestrationWithoutARolePrompt() {
        assertThat(CapabilitySpec.internalOrchestration(List.of()).systemPromptId()).isNull();
    }

    /**
     * R22 验收的后半句：**"并计入 prompt_stamps"**。光能加载不够——stamp 进不了
     * {@code agent_trace.prompt_stamps} 的话，Golden 分数一变就归因不到提示词版本，
     * 而那是 issue #101 建那一列的全部理由。
     *
     * <p>这里钉的是 stamp 的形状（{@code id@version#hash} 三段俱全）；它真的被写进 RunContext
     * 由 {@code AgentLoopExecutorPromptStampTest} 端到端断言。
     */
    @Test
    @DisplayName("模式级提示词的 stamp 三段俱全，能用于 trace 归因")
    void exposesAThreePartStampForTraceAttribution() {
        for (String id : new String[] {"chat.system", "analytics.system"}) {
            assertThat(PROMPTS.get(id).stamp())
                    .as("%s 的 stamp 要形如 id@version#hash，缺一段就少一种归因能力", id)
                    .matches(java.util.regex.Pattern.quote(id) + "@v\\d+#[0-9a-f]{8}");
        }
    }

    @Test
    @DisplayName("两份提示词都能从 PromptRegistry 加载，并登记进版本锁")
    void bothPromptsResolveAndAreVersionLocked() {
        for (String id : new String[] {"chat.system", "analytics.system"}) {
            assertThat(PROMPTS.text(id)).as("%s 必须能加载出正文", id).isNotBlank();
            assertThat(PROMPTS.get(id).stamp()).as("%s 必须有 id@version#hash 标识才能进 trace", id).isNotBlank();
        }
    }

    /**
     * **这条是核心。** 数据分析的提示词必须把"数字只能来自工具返回值"和"没查到数据就不要画图"
     * 写死——它们是 §7.5 那条失败的直接对策，也是 R16 代码硬约束的提示词侧对应物。
     * 两层都要有，任何一层单独都不够：提示词是软约束，代码门禁只覆盖图表这一个出口。
     */
    @Test
    @DisplayName("数据分析提示词写明了「不编数据」和「没数据不画图」")
    void analyticsPromptForbidsFabricatingNumbersAndChartingWithoutData() {
        String analytics = PROMPTS.text("analytics.system");

        assertThat(analytics).contains("工具");
        assertThat(analytics)
                .as("必须明确「每个数字都来自工具返回值」，这是编数据那条失败的直接对策")
                .contains("必须来自");
        assertThat(analytics)
                .as("必须明确「没查到数据就不要调图表工具」")
                .contains("不要调图表工具");
        assertThat(analytics)
                .as("空结果不是错误——不能编，也不能静默返回空")
                .contains("空结果");
    }

    /**
     * 普通对话没有数据库工具，必须**明说自己查不到**并引导用户切模式，而不是编一个数字。
     * 这条和前端的引导卡片（R5 / issue #94）是同一件事的两侧：卡片提示用户，提示词约束模型。
     */
    @Test
    @DisplayName("普通对话提示词写明了「没有数据库工具、不要编数字」")
    void chatPromptAdmitsItHasNoDatabaseAndRefusesToInvent() {
        String chat = PROMPTS.text("chat.system");

        assertThat(chat).contains("没有数据库工具");
        assertThat(chat).contains("数据分析");
        assertThat(chat)
                .as("模拟数据 / 演示数据这条后路必须堵死——事后补一句「以上为模拟数据」同样不行")
                .contains("模拟数据");
    }

    /**
     * R2 的边界：模式级提示词只写"我是谁、边界在哪"，**不写"这类任务按什么套路做"**。
     * SOP 仍在 skills/data-analysis/SKILL.md 里走 Skill 元工具按需加载。把 SOP 搬进系统提示词
     * 等于悄悄推翻 R2 那个带取舍记录的决策——真要退回常驻，依据得是 R2-risk 的实测调用率。
     */
    @Test
    @DisplayName("数据分析提示词把 SOP 留给 Skill，自己只讲边界")
    void analyticsPromptDelegatesTheSopToTheSkillInsteadOfInliningIt() {
        String analytics = PROMPTS.text("analytics.system");

        assertThat(analytics)
                .as("必须显式把「具体怎么做」指向 Skill，否则模型不会去调它")
                .contains("Skill");
        assertThat(analytics.length())
                .as("模式级提示词是边界说明，不是 SOP；长到 SKILL.md 的量级就说明 SOP 被搬进来了")
                .isLessThan(2_000);
    }
}
