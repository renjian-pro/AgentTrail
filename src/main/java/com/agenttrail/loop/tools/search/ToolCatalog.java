package com.agenttrail.loop.tools.search;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 延迟工具池 + 预建检索索引，跨对话请求共享、只在装配时构建一次。
 *
 * <p>索引构建要分词，每个请求都重建就是纯浪费——工具池和索引在应用生命周期里不会变，
 * 会变的只是"这次对话已经发现了哪些工具"，那部分状态见 {@link ToolSearchSession}，
 * 每次对话请求各自持有一份，互不泄漏。
 */
public final class ToolCatalog {

    private final Map<String, ToolCallback> tools;
    private final List<ToolIndexEntry> index;
    private final Map<String, ToolIndexEntry> indexByName;
    private final ToolSearchConfig config;
    private final ChatModel chatModel;

    private ToolCatalog(Map<String, ToolCallback> tools, List<ToolIndexEntry> index,
                        ToolSearchConfig config, ChatModel chatModel) {
        this.tools = tools;
        this.index = index;
        this.indexByName = ToolIndexEntry.indexByName(index);
        this.config = config;
        this.chatModel = chatModel;
    }

    /**
     * @param chatModel LLM 检索模式用；只用 KEYWORD 模式时可传 null
     */
    public static ToolCatalog of(ToolSearchConfig config, List<ToolCallback> deferredTools, ChatModel chatModel) {
        Map<String, ToolCallback> byName = deferredTools.stream().collect(Collectors.toMap(
                tool -> tool.getToolDefinition().name(), Function.identity(), (first, duplicate) -> first));
        return new ToolCatalog(Map.copyOf(byName), ToolIndexEntry.buildIndex(byName), config, chatModel);
    }

    public ToolSearchSession newSession() {
        return new ToolSearchSession(tools, index, indexByName, config, chatModel);
    }

    /** 全部延迟工具，不管有没有被发现——执行层要按这份找到工具，可见性限制只在 LLM 那一侧。 */
    public List<ToolCallback> allTools() {
        return List.copyOf(tools.values());
    }
}
