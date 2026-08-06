package com.agenttrail.loop.memory;

import com.agenttrail.loop.core.SynchronousLlmCall;
import com.agenttrail.loop.structured.JsonRepair;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一轮对话结束后，用一次轻量的 LLM 调用从问答里提取"值得跨对话记住的东西"（issue #19）。
 * 移植自 agentx-core 的 {@code MemoryExtractor}，砍掉了它"提取后再用第二次 LLM 调用跟已有记忆
 * 合并去重"的环节——AC 只要求"提取并写入"，合并冲突处理是这个提取器之上可以再叠加的能力，
 * 不属于这个 ticket 的基础机制；现在的写入策略是单纯追加。
 *
 * <p>提取失败（模型调用异常、返回内容修复后仍不是合法 JSON 数组）一律静默跳过，不抛出去——
 * 参照 {@link com.agenttrail.loop.context.ContextCompactor#compact} 里摘要调用失败时"降级而不是
 * 让整轮对话崩掉"的处理方式，记忆本来就是锦上添花，不能反过来拖累主流程。
 */
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String EXTRACTION_SYSTEM_PROMPT = """
            你是一个记忆提取助手。从用户和助手的对话中，提取值得长期记住的用户信息。

            ## 记忆类型
            - PROFILE: 用户的身份、角色、背景（如：职业、技能、职责）
            - PREFERENCE: 用户的偏好、习惯（如：语言偏好、代码风格）
            - INSTRUCTION: 用户要求的回答方式或行为规则（如：纠正、要求、禁令）
            - FACT: 用户明确提到的关于自身或环境的客观事实（如：使用的工具版本）

            ## 严格规则（必须遵守）
            1. 只提取【用户明确说出】的信息，禁止推测、推断或延伸
            2. 不要提取助手回答中的内容，只关注用户自己说的信息
            3. 不要从对话主题推测用户属性（讨论 Spring 不代表用户用 Spring）
            4. 每条记忆必须是原文或忠实概括，禁止添加对话中未出现的细节
            5. 没有明确信息时返回空数组 []，宁可漏记不可错记

            ## 输出格式
            [{"type": "PROFILE", "content": "..."}]
            只返回 JSON 数组，不要包含其他内容。""";

    private record ExtractionItem(String type, String content) {
    }

    private final ChatModel chatModel;
    private final MemoryStore memoryStore;

    public MemoryExtractor(ChatModel chatModel, MemoryStore memoryStore) {
        this.chatModel = chatModel;
        this.memoryStore = memoryStore;
    }

    public void extractAndSave(String userId, String question, String answer) {
        if (userId == null || question == null || answer == null || answer.isBlank()) {
            return;
        }
        try {
            extract(userId, question, answer).forEach(memoryStore::save);
        } catch (Exception extractionFailed) {
            log.warn("记忆提取失败，跳过本轮记忆更新: {}", extractionFailed.getMessage());
        }
    }

    private List<MemoryItem> extract(String userId, String question, String answer) throws Exception {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(EXTRACTION_SYSTEM_PROMPT),
                new UserMessage("用户: " + question + "\n助手: " + answer)));
        String rawOutput = SynchronousLlmCall.call(chatModel, prompt).getResult().getOutput().getText();
        String fixedJson = JsonRepair.fixJson(rawOutput);
        List<ExtractionItem> items = JSON.readValue(fixedJson, new TypeReference<List<ExtractionItem>>() {
        });
        return toMemoryItems(userId, items);
    }

    private List<MemoryItem> toMemoryItems(String userId, List<ExtractionItem> items) {
        long extractedAt = System.currentTimeMillis();
        List<MemoryItem> result = new ArrayList<>();
        for (ExtractionItem item : items) {
            if (item.type() == null || item.content() == null || item.content().isBlank()) {
                continue;
            }
            try {
                MemoryType type = MemoryType.valueOf(item.type().toUpperCase(Locale.ROOT));
                result.add(new MemoryItem(userId, type, item.content(), extractedAt));
            } catch (IllegalArgumentException unknownType) {
                log.debug("未知记忆类型 {}，跳过该条", item.type());
            }
        }
        return result;
    }
}
