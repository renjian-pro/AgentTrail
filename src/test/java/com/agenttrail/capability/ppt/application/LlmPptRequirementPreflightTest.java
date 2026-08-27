package com.agenttrail.capability.ppt.application;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

class LlmPptRequirementPreflightTest {

    @Test
    void asksForEveryMissingRequirementWithoutStartingGeneration() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {"title":null,"topic":null,"audience":null,"slideCount":0,"tone":null}
                """)));
        PptRequirementPreflight preflight = new LlmPptRequirementPreflight(
                new AgentLoopExecutor(model, List.of(), 3));

        PptPreflightOutcome outcome = preflight.assess("conversation-1", "生成 PPT");

        assertThat(outcome.ready()).isFalse();
        assertThat(outcome.assistantMessage()).contains("主题", "页数", "风格", "受众");
        assertThat(outcome.generationRequest()).isNull();
    }

    @Test
    void returnsACanonicalGenerationRequestOnlyAfterAllRequirementsAreConfirmed() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {"title":"年度工作总结","topic":"年度工作总结与新年计划",
                 "audience":"公司管理层","slideCount":10,"tone":"商务简约"}
                """)));
        PptRequirementPreflight preflight = new LlmPptRequirementPreflight(
                new AgentLoopExecutor(model, List.of(), 3));

        PptPreflightOutcome outcome = preflight.assess("conversation-1", "主题和其它要求都已确认");

        assertThat(outcome.ready()).isTrue();
        assertThat(outcome.assistantMessage()).contains("需求已确认", "年度工作总结", "10 页");
        assertThat(outcome.generationRequest())
                .contains("主题：年度工作总结与新年计划")
                .contains("受众：公司管理层")
                .contains("页数：10")
                .contains("风格：商务简约");
    }
}
