package com.agenttrail.runtime.agent;

import com.agenttrail.runtime.tool.ToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 一个能力包的可声明定义——Phase 3 用它替换 {@code AgentLoopExecutorFactory} 那 5 个 {@code forXxx}
 * 分支，让"加一种能力"变成加一条数据而不是加一个方法。目前尚无生产使用方。
 *
 * @param profileId 按 id 引用运行时装配，而不是内嵌 {@code RuntimeProfile} 实例：定义本身要能从
 *                  yml/DB 反序列化出来，内嵌一个装着 {@code ContextPolicy}/{@code MemoryStore} 等
 *                  运行时协作者的对象既序列化不了，也会让本包反向依赖 {@code loop.profile}
 *                  （Phase -1 打破的正是这条循环）。
 */
public record AgentDefinition(String id, String description, String profileId,
                              List<ToolDefinition> tools, InputContract input,
                              OutputContract output, AgentPolicy policy) {
    public AgentDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("agent id is required");
        profileId = (profileId == null || profileId.isBlank()) ? "default" : profileId;
        tools = tools == null ? List.of() : List.copyOf(tools);
        input = input == null ? new InputContract(Set.of()) : input;
        output = output == null ? new OutputContract("text/plain") : output;
        policy = policy == null ? AgentPolicy.defaults() : policy;
    }

    public record InputContract(Set<String> keywords) {
        public InputContract { keywords = keywords == null ? Set.of() : Set.copyOf(keywords); }
        public boolean matches(String message) {
            String normalized = message == null ? "" : message.toLowerCase();
            return keywords.stream().anyMatch(keyword -> normalized.contains(keyword.toLowerCase()));
        }
    }

    public record OutputContract(String mediaType) { }

    public record AgentPolicy(long maxTokens, Duration timeout, int maxNestingDepth, Set<String> allowedTools) {
        public AgentPolicy {
            if (maxNestingDepth < 0) throw new IllegalArgumentException("maxNestingDepth must be non-negative");
            timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        }
        public static AgentPolicy defaults() { return new AgentPolicy(0, Duration.ofMinutes(2), 3, Set.of()); }
    }
}
