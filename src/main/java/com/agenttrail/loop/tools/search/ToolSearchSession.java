package com.agenttrail.loop.tools.search;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次对话请求内的工具发现状态。请求之间必须相互隔离——不隔离的话，一个会话搜出来的工具
 * 会"泄漏"给另一个并发的会话，模型会看到自己没搜过的工具凭空出现在可选列表里。
 */
public final class ToolSearchSession {

    private final Map<String, ToolCallback> tools;
    private final Set<String> discoveredNames = ConcurrentHashMap.newKeySet();
    private final ToolCallback toolSearchCallback;

    ToolSearchSession(Map<String, ToolCallback> tools, List<ToolIndexEntry> index,
                      ToolSearchConfig config, ChatModel chatModel) {
        this.tools = tools;
        this.toolSearchCallback = new ToolSearchCallback(config, tools, index, chatModel, discoveredNames);
    }

    /** 本轮该暴露给模型的、已经被搜到的延迟工具；没搜到的对模型不可见。 */
    public List<ToolCallback> discoveredTools() {
        return discoveredNames.stream().map(tools::get).filter(Objects::nonNull).toList();
    }

    public ToolCallback toolSearchCallback() {
        return toolSearchCallback;
    }
}
