package com.agenttrail.loop.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 延迟工具发现的元工具：模型传一个查询进来，拿回匹配的工具名单——匹配到的工具要到
 * <b>下一轮</b>才真正可调用（见 {@link ToolSearchSession#discoveredTools()}），本类只负责发现。
 *
 * <p>HYBRID 模式下关键词打分优先、只有零命中时才 fallback 到 LLM 语义检索，不是两条路径都跑：
 * 关键词命中已经足够时多打一次 LLM 是纯浪费的 token 和延迟。
 */
final class ToolSearchCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchCallback.class);

    static final String TOOL_NAME = "search_tools";
    private static final String QUERY_PARAM = "query";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INPUT_SCHEMA = """
            {
              "type" : "object",
              "properties" : {
                "query" : {
                  "type" : "string",
                  "description" : "描述想要完成的事情，用自然语言或关键词都可以"
                }
              },
              "required" : [ "query" ]
            }""";

    private static final String DESCRIPTION = """
            按需发现当前不在工具清单里的能力。传入你想完成的事情的关键词或描述，会返回匹配的工具
            名称与说明。注意：搜到的工具要到**下一轮**才能被真正调用，本轮里不能直接使用它的名字发起调用。
            """;

    private static final String LLM_SEARCH_SYSTEM_PROMPT = """
            你是工具检索助手。给定一个查询和一份候选工具清单（每行"名称: 描述"），
            从候选清单中选出与查询最相关的工具，最多 %d 个。
            只输出一个 JSON 数组，元素是工具名称字符串，原样照抄候选清单里的名称；
            一个都不匹配就输出空数组 []。不要输出任何解释文字，不要输出候选清单之外的名称。
            """;

    private final ToolSearchConfig config;
    private final List<ToolIndexEntry> index;
    private final Map<String, ToolIndexEntry> indexByName;
    private final ChatModel chatModel;
    private final Set<String> discoveredNames;
    private final ToolDefinition definition;

    /**
     * @param indexByName 按名字查表用，来自 {@link ToolCatalog}——跨对话请求共享、只建一次；
     *                     本类不重新扫一遍 index 列表建表，避免每次新会话都重复这份工作。
     *                     "发现之后能不能调用"由 {@link ToolSearchSession} 按名字回查工具表，
     *                     不是本类的职责
     */
    ToolSearchCallback(ToolSearchConfig config, List<ToolIndexEntry> index, Map<String, ToolIndexEntry> indexByName,
                       ChatModel chatModel, Set<String> discoveredNames) {
        this.config = config;
        this.index = index;
        this.indexByName = indexByName;
        this.chatModel = chatModel;
        this.discoveredNames = discoveredNames;
        this.definition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(DESCRIPTION)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        String query = readQuery(toolInput);
        if (query == null || query.isBlank()) {
            return "未提供检索关键词，请在 %s 参数里传入你想完成的事情的描述".formatted(QUERY_PARAM);
        }

        List<ToolIndexEntry> matches = search(query);
        matches.forEach(entry -> discoveredNames.add(entry.name()));
        return renderResult(query, matches);
    }

    /** 模式分派：KEYWORD 永远只用关键词；LLM 永远问模型；HYBRID 关键词零命中才兜底问模型。 */
    private List<ToolIndexEntry> search(String query) {
        return switch (config.mode()) {
            case KEYWORD -> keywordSearch(query);
            case LLM -> (chatModel != null) ? llmSearch(query) : keywordSearch(query);
            case HYBRID -> {
                List<ToolIndexEntry> keywordMatches = keywordSearch(query);
                yield (keywordMatches.isEmpty() && chatModel != null) ? llmSearch(query) : keywordMatches;
            }
        };
    }

    private List<ToolIndexEntry> keywordSearch(String query) {
        List<String> queryTokens = queryTokens(query);
        return index.stream()
                .map(entry -> Map.entry(entry, entry.score(query, queryTokens)))
                .filter(scored -> scored.getValue() > 0)
                .sorted(Comparator.<Map.Entry<ToolIndexEntry, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(config.maxResults())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 普通分词 + camelCase/snake_case 拆词一起用——查询词和索引名称的拆词方式必须对称，
     *  否则查询里写成一个词的 "slackMessage" 永远匹配不上按 ["slack","message"] 建索引的名称分词。 */
    private List<String> queryTokens(String query) {
        List<String> merged = new ArrayList<>(ToolIndexEntry.tokenize(query));
        for (String token : ToolIndexEntry.tokenizeName(query)) {
            if (!merged.contains(token)) {
                merged.add(token);
            }
        }
        return merged;
    }

    /** 失败（模型报错、返回不是合法 JSON）一律降级成空结果，不让检索本身的故障打断整轮对话。 */
    private List<ToolIndexEntry> llmSearch(String query) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(LLM_SEARCH_SYSTEM_PROMPT.formatted(config.maxResults())),
                    new UserMessage("查询: " + query + "\n\n候选工具:\n" + renderCatalog())));
            String content = chatModel.call(prompt).getResult().getOutput().getText();
            return resolveNames(parseNames(content));
        } catch (Exception failure) {
            log.warn("ToolSearch 的 LLM 检索失败，本次返回空结果: {}", failure.getMessage());
            return List.of();
        }
    }

    private String renderCatalog() {
        return index.stream().map(entry -> entry.name() + ": " + entry.description())
                .collect(Collectors.joining("\n"));
    }

    /** 只接受候选清单里真实存在的名称——模型凭空捏造的名字直接丢弃，而不是当成"发现"了它。 */
    private List<ToolIndexEntry> resolveNames(List<String> names) {
        List<ToolIndexEntry> resolved = new ArrayList<>();
        for (String name : names) {
            ToolIndexEntry entry = indexByName.get(name);
            if (entry != null && !resolved.contains(entry)) {
                resolved.add(entry);
            }
            if (resolved.size() >= config.maxResults()) {
                break;
            }
        }
        return resolved;
    }

    private List<String> parseNames(String content) {
        if (content == null) {
            return List.of();
        }
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end < start) {
            return List.of();
        }
        try {
            JsonNode array = JSON.readTree(content.substring(start, end + 1));
            if (!array.isArray()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            array.forEach(node -> {
                if (node.isTextual()) {
                    names.add(node.textValue());
                }
            });
            return names;
        } catch (Exception malformed) {
            return List.of();
        }
    }

    private String readQuery(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(toolInput).get(QUERY_PARAM);
            return (node == null || node.isNull()) ? null : node.asText();
        } catch (Exception malformed) {
            return null;
        }
    }

    private String renderResult(String query, List<ToolIndexEntry> matches) {
        if (matches.isEmpty()) {
            return "未找到与 \"%s\" 匹配的工具。换一种描述再试，或者直接用你已有的工具完成任务。".formatted(query);
        }
        ArrayNode array = JSON.createArrayNode();
        for (ToolIndexEntry entry : matches) {
            ObjectNode item = JSON.createObjectNode();
            item.put("name", entry.name());
            item.put("description", entry.description());
            array.add(item);
        }
        return array.toString();
    }
}
