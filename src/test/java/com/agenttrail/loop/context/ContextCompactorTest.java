package com.agenttrail.loop.context;

import com.agenttrail.loop.core.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两层压缩，类比 JVM 的分代回收：
 * <ul>
 *   <li><b>micro_compact</b> 像 Young GC——每轮都跑、很便宜，只把"老而大"的工具内容换成占位符，
 *       消息结构完全不变
 *   <li><b>auto_compact</b> 像 Full GC——只有真快撑爆窗口时才触发，代价是一次额外的 LLM 摘要调用
 * </ul>
 */
class ContextCompactorTest {

    private static final ChatModel UNUSED_MODEL = new ScriptedChatModel();

    // ==================== Layer 1: micro_compact ====================

    @Test
    void leavesRecentToolResultsUntouched() {
        ContextPolicy policy = ContextPolicy.builder().keepRecentTools(4).maxToolLength(10).build();
        List<Message> messages = conversationWith(2);

        new ContextCompactor(policy, UNUSED_MODEL).compact(messages, "现在问什么");

        assertThat(toolResponseTextAt(messages, 0)).doesNotContain("compacted");
    }

    /** 老的工具结果才压，且必须压成合法 JSON——占位符本身进的是 tool 消息，格式错了模型会解析失败。 */
    @Test
    void replacesOlderToolResultsWithAValidJsonPlaceholder() {
        ContextPolicy policy = ContextPolicy.builder().keepRecentTools(1).maxToolLength(10).build();
        List<Message> messages = conversationWith(3);

        new ContextCompactor(policy, UNUSED_MODEL).compact(messages, "现在问什么");

        assertThat(toolResponseTextAt(messages, 0))
                .contains("\"compacted\":true")
                .contains("\"originalLength\"");
        assertThat(toolResponseTextAt(messages, 2))
                .as("最近一组必须保留原文").doesNotContain("compacted");
    }

    /** 两个方向都要压：工具的返回值会很大，模型发出的调用参数同样会很大（比如一大段 SQL）。 */
    @Test
    void alsoShrinksOversizedToolCallArguments() {
        ContextPolicy policy = ContextPolicy.builder().keepRecentTools(1).maxToolLength(10).build();
        List<Message> messages = conversationWith(3);

        new ContextCompactor(policy, UNUSED_MODEL).compact(messages, "现在问什么");

        assertThat(toolCallArgumentsAt(messages, 0)).contains("\"compacted\":true");
    }

    @Test
    void keepsToolContentShorterThanTheThreshold() {
        ContextPolicy policy = ContextPolicy.builder().keepRecentTools(0).maxToolLength(10_000).build();
        List<Message> messages = conversationWith(3);

        new ContextCompactor(policy, UNUSED_MODEL).compact(messages, "现在问什么");

        assertThat(toolResponseTextAt(messages, 0)).doesNotContain("compacted");
    }

    /** 受保护的工具（技能内容、待办清单）永不压缩——它们是持续有效的指令，不是一次性结果。 */
    @Test
    void neverCompactsProtectedTools() {
        ContextPolicy policy = ContextPolicy.builder()
                .keepRecentTools(0).maxToolLength(10).protectedTools("Skill").build();
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("原始提问"),
                assistantCalling("call-1", "Skill", "{\"name\":\"" + "s".repeat(200) + "\"}"),
                toolResult("call-1", "Skill", "技能全文".repeat(100)),
                assistantCalling("call-2", "executeSql", "{\"sql\":\"" + "x".repeat(200) + "\"}"),
                toolResult("call-2", "executeSql", "查询结果".repeat(100))));

        new ContextCompactor(policy, UNUSED_MODEL).compact(messages, "现在问什么");

        assertThat(toolResponseTextAt(messages, 0)).as("技能内容必须完整保留").doesNotContain("compacted");
        assertThat(toolResponseTextAt(messages, 1)).as("普通工具结果照压").contains("compacted");
    }

    // ==================== Layer 0: 按标记只保留最新一条 ====================

    /** 多条消息都以同一个标记开头时，只留最靠后的一条，更早的整条从列表里消失（issue #37）。 */
    @Test
    void keepsOnlyTheLatestMessageMatchingARetainLatestOnlyMarker() {
        ContextPolicy policy = ContextPolicy.builder().retainLatestOnlyMarkers("[FEEDBACK]").build();
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("原始提问"),
                new UserMessage("[FEEDBACK]第一轮反馈"),
                new UserMessage("普通消息，不该受影响"),
                new UserMessage("[FEEDBACK]第二轮反馈")));

        new ContextCompactor(policy, UNUSED_MODEL).compact(messages, "现在问什么");

        assertThat(messages).hasSize(3);
        assertThat(messages).noneMatch(m -> m.getText().contains("第一轮反馈"));
        assertThat(messages).anyMatch(m -> m.getText().contains("第二轮反馈"));
        assertThat(messages).anyMatch(m -> m.getText().equals("普通消息，不该受影响"));
    }

    /** 只有一条消息命中标记时，没有"更早的"可丢——原样保留。 */
    @Test
    void leavesASingleMarkedMessageUntouched() {
        ContextPolicy policy = ContextPolicy.builder().retainLatestOnlyMarkers("[FEEDBACK]").build();
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("原始提问"),
                new UserMessage("[FEEDBACK]唯一一轮反馈")));

        new ContextCompactor(policy, UNUSED_MODEL).compact(messages, "现在问什么");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getText()).contains("唯一一轮反馈");
    }

    /** 没配置任何标记时（默认），这条规则完全不生效，和引入这个机制之前行为一致。 */
    @Test
    void doesNothingWhenNoRetainLatestOnlyMarkersAreConfigured() {
        ContextPolicy policy = ContextPolicy.builder().build();
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("[FEEDBACK]第一轮反馈"),
                new UserMessage("[FEEDBACK]第二轮反馈")));
        List<Message> before = List.copyOf(messages);

        new ContextCompactor(policy, UNUSED_MODEL).compact(messages, "现在问什么");

        assertThat(messages).containsExactlyElementsOf(before);
    }

    // ==================== Layer 2: auto_compact ====================

    @Test
    void summarisesTheWholeHistoryOnceTheTokenBudgetIsBlown() {
        ContextPolicy policy = ContextPolicy.builder().tokenThreshold(50).maxToolLength(0).build();
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("你是一个助手"),
                new UserMessage("原始提问".repeat(200)),
                new AssistantMessage("一段很长的回答".repeat(200))));
        ChatModel summariser = summarisingModelReturning("这是摘要");

        new ContextCompactor(policy, summariser).compact(messages, "现在问什么");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1).getText()).contains("这是摘要");
    }

    /** 系统提示词定义了 Agent 的身份和规则，压掉它模型会当场"人格分裂"，必须原样保留。 */
    @Test
    void alwaysKeepsTheSystemPromptOutOfTheSummary() {
        ContextPolicy policy = ContextPolicy.builder().tokenThreshold(50).maxToolLength(0).build();
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("你是一个 SQL 分析助手"),
                new UserMessage("原始提问".repeat(200)),
                new AssistantMessage("一段很长的回答".repeat(200))));

        new ContextCompactor(policy, summarisingModelReturning("摘要")).compact(messages, "现在问什么");

        assertThat(messages.get(0).getText()).isEqualTo("你是一个 SQL 分析助手");
    }

    /** 摘要要靠一次额外的 LLM 调用，它自己也可能失败——不能因此让整轮对话崩掉。 */
    @Test
    void fallsBackToKeepingRecentTurnsWhenTheSummaryCallFails() {
        ContextPolicy policy = ContextPolicy.builder().tokenThreshold(50).maxToolLength(0).build();
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("你是一个助手"),
                new UserMessage("原始提问".repeat(200)),
                new AssistantMessage("最后一条回答".repeat(200))));

        new ContextCompactor(policy, failingModel()).compact(messages, "现在问什么");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getText())
                .as("降级后仍要保住最近的对话内容，不能变成空白")
                .contains("最后一条回答");
    }

    @Test
    void doesNothingToAConversationThatFitsComfortably() {
        ContextPolicy policy = ContextPolicy.builder().tokenThreshold(100_000).maxToolLength(10_000).build();
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("你是一个助手"),
                new UserMessage("你好"),
                new AssistantMessage("你好，有什么可以帮你")));
        List<Message> before = List.copyOf(messages);

        new ContextCompactor(policy, UNUSED_MODEL).compact(messages, "你好");

        assertThat(messages).containsExactlyElementsOf(before);
    }

    // ==================== 测试辅助 ====================

    /** 构造 {@code rounds} 组「助手发起工具调用 → 工具返回结果」的历史。 */
    private static List<Message> conversationWith(int rounds) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("原始提问"));
        for (int i = 0; i < rounds; i++) {
            String id = "call-" + i;
            messages.add(assistantCalling(id, "executeSql", "{\"sql\":\"" + "x".repeat(200) + "\"}"));
            messages.add(toolResult(id, "executeSql", "查询结果".repeat(100)));
        }
        return messages;
    }

    private static AssistantMessage assistantCalling(String id, String tool, String arguments) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", tool, arguments)))
                .build();
    }

    private static ToolResponseMessage toolResult(String id, String tool, String content) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, tool, content)))
                .build();
    }

    /** 取第 {@code ordinal} 个工具结果消息的内容。 */
    private static String toolResponseTextAt(List<Message> messages, int ordinal) {
        return messages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .toList().get(ordinal)
                .getResponses().get(0).responseData();
    }

    /** 取第 {@code ordinal} 个带工具调用的助手消息的参数。 */
    private static String toolCallArgumentsAt(List<Message> messages, int ordinal) {
        return messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .toList().get(ordinal)
                .getToolCalls().get(0).arguments();
    }

    private static ChatModel summarisingModelReturning(String summary) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return text(summary);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("摘要走同步调用");
            }
        };
    }

    private static ChatModel failingModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("模型不可用");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("摘要走同步调用");
            }
        };
    }
}
