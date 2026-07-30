package com.agenttrail.loop.skills;

import com.agenttrail.loop.context.ContextPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单 mega-tool 设计的行为约束（踩坑点 #15）。
 *
 * <p>本类要守住的核心不变量只有一条：**不论有多少个技能，暴露给模型的永远只有一个工具**。
 * 早期的两段式设计（技能清单写进 system prompt + 一个 read_skill 工具）让模型频繁把技能名
 * 当成独立工具直接调——清单和可调工具处在两个不同位置，模型看到一堆名字会本能地想调它们。
 * 把清单挪进这一个工具自己的 description 之后，"看到技能"和"调用工具"变成同一处上下文，
 * 没有跨位置的联想跳跃可犯。
 */
class SkillsToolTest {

    @TempDir
    Path skillsRoot;

    // ==================== 单工具不变量 ====================

    /** 三个技能 → 一个工具。技能名不会成为任何工具的名字，模型没有可以误调的目标。 */
    @Test
    void exposesExactlyOneToolNoMatterHowManySkillsExist() throws IOException {
        List<Skill> skills = List.of(
                skill("pptx", "生成演示文稿"),
                skill("data-analysis", "自然语言查数据"),
                skill("domain-namer", "起域名"));

        List<ToolCallback> tools = SkillsTool.of(skills).stream().toList();

        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().getToolDefinition().name()).isEqualTo(SkillsTool.TOOL_NAME);
        assertThat(tools.stream().map(t -> t.getToolDefinition().name()))
                .doesNotContain("pptx", "data-analysis", "domain-namer");
    }

    /**
     * 工具名必须正好是 {@code Skill}——上下文压缩把这个名字写死在了内置保护清单里，
     * 改名会让技能内容在长会话里被压掉，模型中途忘掉自己领的是什么活。
     */
    @Test
    void usesTheToolNameThatContextCompactionProtects() throws IOException {
        ToolCallback tool = SkillsTool.of(List.of(skill("pptx", "生成演示文稿"))).orElseThrow();

        assertThat(ContextPolicy.defaults().isProtected(tool.getToolDefinition().name())).isTrue();
    }

    /** 技能清单在工具自己的描述里，不在别处——这就是 #15 的解法本身。 */
    @Test
    void rendersEverySkillIntoItsOwnDescription() throws IOException {
        ToolCallback tool = SkillsTool.of(List.of(
                skill("pptx", "生成演示文稿"),
                skill("data-analysis", "自然语言查数据"))).orElseThrow();

        String description = tool.getToolDefinition().description();

        assertThat(description)
                .contains("<skill>")
                .contains("<name>pptx</name>")
                .contains("<description>生成演示文稿</description>")
                .contains("<name>data-analysis</name>")
                .contains("<description>自然语言查数据</description>");
    }

    /** 描述里必须写死"禁止把技能名当工具调"，这是对 #15 那次失败的直接补救。 */
    @Test
    void forbidsCallingSkillNamesAsToolsInTheDescription() throws IOException {
        ToolCallback tool = SkillsTool.of(List.of(skill("pptx", "生成演示文稿"))).orElseThrow();

        assertThat(tool.getToolDefinition().description())
                .contains("技能名称当作独立的工具");
    }

    /** 技能正文（可能上千行）绝不进描述，只有摘要进——渐进式披露的全部意义就在这里。 */
    @Test
    void keepsSkillBodyOutOfTheDescription() throws IOException {
        ToolCallback tool = SkillsTool.of(List.of(skill("pptx", "生成演示文稿"))).orElseThrow();

        assertThat(tool.getToolDefinition().description()).doesNotContain("技能正文-pptx");
    }

    /** 同样的输入必须产出逐字节相同的描述，否则提示词前缀每次都变，prompt 缓存直接失效。 */
    @Test
    void producesAByteIdenticalDescriptionForTheSameSkills() throws IOException {
        List<Skill> skills = List.of(skill("a", "第一个"), skill("b", "第二个"), skill("c", "第三个"));

        String first = SkillsTool.of(skills).orElseThrow().getToolDefinition().description();
        String second = SkillsTool.of(skills).orElseThrow().getToolDefinition().description();

        assertThat(second).isEqualTo(first);
    }

    /** 描述里的技能是 XML，运营写的描述带尖括号/&amp; 会把这段 XML 撑破，必须转义。 */
    @Test
    void escapesXmlSpecialCharactersInSkillMetadata() throws IOException {
        ToolCallback tool = SkillsTool.of(List.of(
                skill("compare", "用 <table> 对比 A & B"))).orElseThrow();

        assertThat(tool.getToolDefinition().description())
                .contains("&lt;table&gt;")
                .contains("A &amp; B")
                .doesNotContain("<table>");
    }

    /**
     * 一个技能都没启用时返回空，而不是抛异常。
     *
     * <p>参考实现在 build() 里 {@code Assert.notEmpty(skills)}——在"每次请求按 DB 现状重建"的
     * 模型下，运营把最后一个技能停用就会让**每一次对话请求**在装配阶段直接崩掉。
     * 没有技能是完全正常的状态，正确的表达是"这一轮不挂 Skill 工具"。
     */
    @Test
    void yieldsNoToolWhenNoSkillIsEnabled() {
        Optional<ToolCallback> tool = SkillsTool.of(List.of());

        assertThat(tool).isEmpty();
    }

    // ==================== 调用语义 ====================

    @Test
    void returnsFullSkillContentAndWorkingDirectory() throws IOException {
        Skill pptx = skill("pptx", "生成演示文稿");
        ToolCallback tool = SkillsTool.of(List.of(pptx)).orElseThrow();

        String result = tool.call("{\"command\":\"pptx\"}");

        assertThat(result)
                .contains(pptx.directory().toAbsolutePath().toString())
                .contains("技能正文-pptx");
    }

    /**
     * 模型幻觉出一个不存在的技能名：返回可用清单让它下一轮自己纠正，不抛异常。
     * 抛异常会顺着工具执行层冒上去，一次拼错名字就打断整轮对话。
     */
    @Test
    void answersUnknownSkillWithTheAvailableList() throws IOException {
        ToolCallback tool = SkillsTool.of(List.of(skill("pptx", "生成演示文稿"))).orElseThrow();

        String result = tool.call("{\"command\":\"nonexistent\"}");

        assertThat(result).contains("nonexistent").contains("pptx");
    }

    /**
     * 参数为空对象：这不是假想输入。maxTokens 把模型输出从中间截断时，半截 JSON 解析失败会被
     * 上游降级成 {@code {}}（踩坑点 #47 / #2），工具侧必须能给出可读的回复而不是崩掉。
     */
    @Test
    void degradesGracefullyWhenArgumentsWereTruncatedAway() throws IOException {
        ToolCallback tool = SkillsTool.of(List.of(skill("pptx", "生成演示文稿"))).orElseThrow();

        assertThat(tool.call("{}")).contains("pptx");
        assertThat(tool.call("")).contains("pptx");
        assertThat(tool.call("{\"command\":\"\"}")).contains("pptx");
        assertThat(tool.call("{ 截断的半截 json")).contains("pptx");
    }

    /** 技能名同样是不可信输入：模型传 {@code ../} 进来不能变成一次目录穿越读取。 */
    @Test
    void refusesTraversingSkillNamesComingFromTheModel() throws IOException {
        ToolCallback tool = SkillsTool.of(List.of(skill("pptx", "生成演示文稿"))).orElseThrow();

        String result = tool.call("{\"command\":\"../../../etc/passwd\"}");

        assertThat(result).doesNotContain("技能正文");
        assertThat(result).contains("pptx");
    }

    /** 声明了 command 参数的 schema，模型才知道该传什么。 */
    @Test
    void declaresASingleCommandParameter() throws IOException {
        ToolCallback tool = SkillsTool.of(List.of(skill("pptx", "生成演示文稿"))).orElseThrow();

        assertThat(tool.getToolDefinition().inputSchema()).contains("command");
    }

    // ==================== 从磁盘装载 ====================

    @Test
    void loadsSkillFromDirectory() throws IOException {
        writeSkillFile("pptx", "生成演示文稿");

        Skill loaded = Skill.load(skillsRoot.resolve("pptx")).orElseThrow();

        assertThat(loaded.name()).isEqualTo("pptx");
        assertThat(loaded.description()).isEqualTo("生成演示文稿");
        assertThat(loaded.content()).contains("技能正文-pptx");
    }

    /** frontmatter 没写 name 时退回目录名，不能变成空字符串键——那样这个技能永远调不到。 */
    @Test
    void fallsBackToDirectoryNameWhenFrontmatterHasNoName() throws IOException {
        Path directory = Files.createDirectories(skillsRoot.resolve("nameless"));
        Files.writeString(directory.resolve("SKILL.md"), "---\ndescription: 没写名字\n---\n正文");

        assertThat(Skill.load(directory).orElseThrow().name()).isEqualTo("nameless");
    }

    @Test
    void ignoresDirectoryWithoutSkillFile() throws IOException {
        Path directory = Files.createDirectories(skillsRoot.resolve("not-a-skill"));
        Files.writeString(directory.resolve("README.md"), "无关文件");

        assertThat(Skill.load(directory)).isEmpty();
    }

    // ==================== 测试夹具 ====================

    private Skill skill(String name, String description) throws IOException {
        writeSkillFile(name, description);
        return Skill.load(skillsRoot.resolve(name)).orElseThrow();
    }

    private void writeSkillFile(String name, String description) throws IOException {
        Path directory = Files.createDirectories(skillsRoot.resolve(name));
        Files.writeString(directory.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---

                技能正文-%s
                """.formatted(name, description, name));
    }
}
