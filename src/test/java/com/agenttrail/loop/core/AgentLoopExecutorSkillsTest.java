package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.skills.SkillManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Skill 工具接入 loop 之后的端到端行为：{@link SkillManager#buildSkillsTool()} 必须真的进了
 * 每一轮喂给模型的工具清单（可见），模型调用它时也必须能被解析执行（可执行）——
 * 光挂可见性、执行层查不到，或者反过来，都只是看起来接上了。
 */
class AgentLoopExecutorSkillsTest {

    @Test
    void skillToolIsVisibleAndExecutableWhenSkillManagerIsConfigured() {
        RecordingToolCallback skillTool = new RecordingToolCallback(
                "Skill", "加载技能", "技能工作目录: /skills/data-analysis\n\n数据分析 SOP 正文");
        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.buildSkillsTool()).thenReturn(Optional.of(skillTool));

        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "Skill", "{\"command\":\"data-analysis\"}")),
                List.of(text("已按数据分析 SOP 完成"))
        );
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .skillManager(skillManager)
                .build();

        List<AgentStreamEvent> events = executor
                .stream("帮我分析一下活跃用户数据", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chatModel.toolNamesAtRound(0)).contains("Skill");
        assertThat(skillTool.recordedArguments()).containsExactly("{\"command\":\"data-analysis\"}");
        assertThat(events).contains(new AgentStreamEvent.ToolEnd("Skill", "call-1",
                "技能工作目录: /skills/data-analysis\n\n数据分析 SOP 正文"));
    }

    @Test
    void behavesExactlyAsBeforeWhenNoSkillManagerIsConfigured() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("done")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5).build();

        executor.stream("随便聊聊", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        assertThat(chatModel.toolNamesAtRound(0)).doesNotContain("Skill");
    }

    @Test
    void noExtraToolIsAddedWhenNoSkillIsCurrentlyEnabled() {
        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.buildSkillsTool()).thenReturn(Optional.empty());

        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("done")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .skillManager(skillManager)
                .build();

        executor.stream("随便聊聊", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        assertThat(chatModel.toolNamesAtRound(0)).doesNotContain("Skill");
    }

    /** 每一轮都现取一次——技能中途被后台停用，下一轮模型就再也看不到它，不用等新会话。 */
    @Test
    void reflectsSkillManagerStateFreshOnEveryRoundNotJustOnce() {
        RecordingToolCallback skillTool = new RecordingToolCallback("Skill", "加载技能", "ok");
        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.buildSkillsTool())
                .thenReturn(Optional.of(skillTool))
                .thenReturn(Optional.empty());

        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "Skill", "{\"command\":\"data-analysis\"}")),
                List.of(text("done"))
        );
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .skillManager(skillManager)
                .build();

        executor.stream("先用后停", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        assertThat(chatModel.toolNamesAtRound(0)).contains("Skill");
        assertThat(chatModel.toolNamesAtRound(1)).doesNotContain("Skill");
    }
}
