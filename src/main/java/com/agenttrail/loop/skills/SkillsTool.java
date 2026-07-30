package com.agenttrail.loop.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 技能的渐进式披露：无论挂了多少个技能，**暴露给模型的永远只有这一个工具**。
 *
 * <h2>为什么是单 mega-tool，而不是"先 list 再 load"的双工具（踩坑点 #15）</h2>
 *
 * <p>最早的设计是两段式：把技能清单写进 system prompt，另外注册一个 {@code read_skill(name)}
 * 工具让模型去取正文。跑起来之后模型频繁**把技能名直接当成工具调用**——比如直接发起一个
 * 名为 {@code autumnsgrove-pptx} 的工具调用，而不是 {@code read_skill("autumnsgrove-pptx")}。
 * 原因不难理解：技能清单在 system prompt 里，真正能调的工具在 tool schema 里，两者处在
 * 两个不同的位置。模型看到一串"名字"，本能反应就是去调用它们，而它并不知道这些名字
 * 不在可调用清单上。
 *
 * <p>解法不是加更多禁止性提示词去对抗这个本能，而是顺应它：把全部技能的摘要直接渲染进
 * **这一个工具自己的 description**。这样"看到技能"和"调用工具"落在同一处上下文里，
 * 模型不需要做任何跨位置的联想，也就没有可犯的联想错误。清单用 XML 而不是 JSON/Markdown，
 * 是因为带闭合标签的结构嵌在一大段自然语言说明里最容易被读成"一份清单"而不是说明的一部分。
 *
 * <h2>另外两个不显眼但要紧的点</h2>
 * <ul>
 *   <li>工具名固定为 {@link #TOOL_NAME}，和上下文压缩的内置保护清单对齐。技能正文是
 *       **持续有效的指令**而不是一次性查询结果，被压掉等于让模型中途忘掉自己领的是什么活。
 *   <li>没有任何技能时返回 {@link Optional#empty()} 而不是抛异常。参考实现在 build() 里
 *       断言技能非空，配上"每次请求按 DB 现状重建"的装配方式，运营停用最后一个技能
 *       就会让每一次对话请求在装配阶段直接崩掉。"这一轮没有技能"是正常状态。
 * </ul>
 */
public final class SkillsTool implements ToolCallback {

    /**
     * 工具名。不要改——{@code ContextPolicy} 的内置保护清单按这个字面量匹配，
     * 改名会让技能正文在长会话里被压缩掉。
     */
    public static final String TOOL_NAME = "Skill";

    /** 唯一的入参名。和描述里第二步写的"传入技能的 name 字段值"对应。 */
    static final String COMMAND_PARAM = "command";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INPUT_SCHEMA = """
            {
              "type" : "object",
              "properties" : {
                "command" : {
                  "type" : "string",
                  "description" : "要加载的技能名称（仅传名称，不含任何参数），取自下方可用技能列表里的 name"
                }
              },
              "required" : [ "command" ]
            }""";

    /**
     * 工具描述模板。{@code %s} 处填入全部技能的 XML 清单。
     *
     * <p>"技能不是工具"和"严格禁止"这两段是 #15 那次失败的直接补救：模型误调技能名的
     * 根因虽然是位置分离，但把这条规则显式写下来，等于在同一处上下文里既给了清单、
     * 又给了用法，成本极低。
     */
    private static final String DESCRIPTION_TEMPLATE = """
            在当前会话中加载一个技能（Skill）。本工具的唯一作用是：传入技能名称，获取该技能的完整提示词和工作目录。

            <什么是技能>
            技能是一段专业的提示词，包含特定领域的知识、工作流程和操作指令。
            每个技能通常还附带参考文件、模板、脚本等资源，存放在技能工作目录中。
            </什么是技能>

            <技能的完整使用流程>
            第一步 — 判断是否需要技能：
              当用户要求完成某项任务时，先检查下方 <可用技能列表> 中是否有匹配的技能。
              如果有匹配的技能，进入第二步；如果没有，直接用你自身能力回答即可。

            第二步 — 通过本工具加载技能：
              调用本工具，传入技能的 name 字段值（仅传名称，不含任何参数）。
              调用后你会收到：技能工作目录路径 + 技能的完整提示词内容。

            第三步 — 阅读并理解技能提示词：
              仔细阅读技能返回的完整提示词，理解该技能的工作流程和要求。

            第四步 — 按技能提示词执行任务：
              严格按照技能提示词中的指令和流程来完成任务。
              如果技能工作目录中有参考文件、模板、脚本，根据需要读取和使用它们。
              使用其他工具来完成技能要求的具体操作。
            </技能的完整使用流程>

            <关键概念：技能不是工具>
            技能（Skill）和工具（Tool）是两个不同的概念：
            - 工具：你可以直接调用的能力，如文件读写、命令执行等
            - 技能：是一段提示词/指令，通过本工具加载后，你按照其中的指引去行动
            技能本身不是工具，不能被当作工具调用。技能的正确使用方式是：
            用本工具加载 → 阅读提示词 → 按提示词中的指令，使用真正的工具来完成任务
            </关键概念：技能不是工具>

            <严格禁止>
            - 禁止将技能名称当作独立的工具来调用
            - 禁止在未通过本工具加载的情况下，假装已经知道技能的内容
            - 禁止编造或猜测 <可用技能列表> 中不存在的技能名称
            - 禁止重复加载同一个技能（同一技能在一次对话中只需加载一次）
            - 禁止加载技能后忽略其提示词内容，自行发挥
            </严格禁止>

            <可用技能列表>
            %s
            </可用技能列表>
            """;

    private final Map<String, Skill> skillsByName;

    private final ToolDefinition definition;

    private SkillsTool(Map<String, Skill> skillsByName) {
        this.skillsByName = skillsByName;
        this.definition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(renderDescription(skillsByName.values()))
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    /**
     * 按这一批技能装配出**一个**工具。
     *
     * @return 有技能时是唯一的那个工具；一个技能都没有时为空——调用方这一轮就不挂 Skill 工具
     */
    public static Optional<ToolCallback> of(Collection<Skill> skills) {
        Map<String, Skill> byName = indexByName(skills);
        return byName.isEmpty() ? Optional.empty() : Optional.of(new SkillsTool(byName));
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    /**
     * 模型传技能名进来，拿回工作目录 + 全文。
     *
     * <p>四类异常输入全部降级成"可读的回复"而不是抛异常，让模型下一轮自己纠正——
     * 抛出去会顺着工具执行层冒上来打断整轮对话，而这些输入没有一个是真的致命：
     * <ul>
     *   <li>参数不是合法 JSON / 是空对象：{@code maxTokens} 把模型输出从中间截断时，
     *       半截 JSON 会被降级成 {@code {}}（踩坑点 #47：症状是"参数莫名其妙为空"，
     *       根因却在一个看起来毫不相干的 token 上限配置上）
     *   <li>技能名拼错或凭空捏造：回一份可用清单，比一句"未找到"有用得多
     * </ul>
     *
     * <p>顺带一提，这里做的是**内存 map 查表**，从不拿模型传来的名字去 resolve 磁盘路径。
     * 路径穿越在这条路上是结构性不可能的，不是靠一层字符串校验挡住的。
     */
    @Override
    public String call(String toolInput) {
        String command = readCommand(toolInput);
        if (command == null || command.isBlank()) {
            return "未指定要加载的技能名称。请重新调用本工具，并在 %s 参数里传入技能名。当前可用技能: %s"
                    .formatted(COMMAND_PARAM, availableNames());
        }

        Skill skill = skillsByName.get(command.trim());
        if (skill == null) {
            return "未找到技能: %s。当前可用技能: %s".formatted(command.trim(), availableNames());
        }
        return "技能工作目录: %s%n%n%s".formatted(skill.directory().toAbsolutePath(), skill.content());
    }

    private String readCommand(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(toolInput).get(COMMAND_PARAM);
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception malformed) {
            return null;
        }
    }

    private String availableNames() {
        return String.join(", ", skillsByName.keySet());
    }

    private static String renderDescription(Collection<Skill> skills) {
        return DESCRIPTION_TEMPLATE.formatted(
                skills.stream().map(Skill::toXml).collect(Collectors.joining("\n")));
    }

    /**
     * 建索引时保持入参顺序（{@link LinkedHashMap}），并且**先到先得**。
     *
     * <p>顺序要稳：这份 map 决定了描述里技能的排列顺序，而描述是提示词前缀的一部分，
     * 顺序抖动会让 prompt 缓存每次都落空。
     *
     * <p>先到先得而不是后来覆盖：两个目录的 frontmatter 写了同一个 name 时，
     * 保留先扫到的那个并把重复的丢掉——重要的是这个决定是确定性的，
     * 而不是像参考实现那样由 {@code HashMap.put} 的调用顺序悄悄决定。
     */
    private static Map<String, Skill> indexByName(Collection<Skill> skills) {
        Map<String, Skill> byName = new LinkedHashMap<>();
        if (skills == null) {
            return byName;
        }
        for (Skill skill : skills) {
            if (skill != null && skill.name() != null && !skill.name().isBlank()) {
                byName.putIfAbsent(skill.name(), skill);
            }
        }
        return byName;
    }
}
