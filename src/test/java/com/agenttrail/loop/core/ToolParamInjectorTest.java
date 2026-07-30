package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static com.agenttrail.loop.core.support.RecordingToolCallback.schemaWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态参数双通道的执行侧（踩坑点 #59）。
 *
 * <p>系统级参数（userId、租户 id 这类）绝不能靠"写进提示词让模型复制到工具参数里"——
 * 模型会漏填、会填错、更要命的是可以被用户诱导填成别人的 id，直接就是越权。
 * 正确做法是这些参数模型完全看不见，由 Runtime 在工具执行前强制注入、覆盖模型给的值。
 *
 * <p>注入还必须按目标工具自己声明的 inputSchema 做白名单过滤：不是每个工具都需要 userId，
 * 无差别注入会给不相干的工具塞进它根本不认识的字段，轻则被工具的严格模式拒绝，重则语义错乱。
 */
class ToolParamInjectorTest {

    @Test
    void injectsSystemParametersDeclaredByTheTargetTool() {
        ToolDefinition definition = new RecordingToolCallback(
                "executeSql", "runs sql", schemaWith("sql", "userId"), "ok").getToolDefinition();
        ToolParamInjector injector = new ToolParamInjector(Map.of("userId", "u-42"));

        String merged = injector.inject("{\"sql\":\"select 1\"}", definition);

        assertThat(merged).contains("\"userId\":\"u-42\"").contains("\"sql\":\"select 1\"");
    }

    @Test
    void overridesWhateverTheModelSuppliedForASystemParameter() {
        ToolDefinition definition = new RecordingToolCallback(
                "executeSql", "runs sql", schemaWith("sql", "userId"), "ok").getToolDefinition();
        ToolParamInjector injector = new ToolParamInjector(Map.of("userId", "u-42"));

        String merged = injector.inject("{\"sql\":\"select 1\",\"userId\":\"u-999\"}", definition);

        assertThat(merged).contains("\"userId\":\"u-42\"").doesNotContain("u-999");
    }

    @Test
    void skipsParametersTheTargetToolDoesNotDeclare() {
        ToolDefinition definition = new RecordingToolCallback(
                "currentTime", "tells the time", schemaWith("timezone"), "ok").getToolDefinition();
        ToolParamInjector injector = new ToolParamInjector(Map.of("userId", "u-42"));

        String merged = injector.inject("{\"timezone\":\"UTC\"}", definition);

        assertThat(merged).doesNotContain("userId").contains("\"timezone\":\"UTC\"");
    }

    @Test
    void leavesArgumentsUntouchedWhenThereAreNoSystemParameters() {
        ToolDefinition definition = new RecordingToolCallback(
                "echo", "echoes", schemaWith("text"), "ok").getToolDefinition();
        ToolParamInjector injector = new ToolParamInjector(Map.of());

        String merged = injector.inject("{\"text\":\"ping\"}", definition);

        assertThat(merged).isEqualTo("{\"text\":\"ping\"}");
    }

    /** schema 缺失/不可解析时保守处理：不注入，也不炸——宁可少注入也不能污染工具参数。 */
    @Test
    void injectsNothingWhenTheToolDeclaresNoUsableSchema() {
        ToolDefinition definition = new RecordingToolCallback("legacy", "no schema", "ok").getToolDefinition();
        ToolParamInjector injector = new ToolParamInjector(Map.of("userId", "u-42"));

        String merged = injector.inject("{\"text\":\"ping\"}", definition);

        assertThat(merged).doesNotContain("userId");
    }
}
