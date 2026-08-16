package com.agenttrail.loop.security;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Set;

/**
 * "消费型工具必须有数据来源"策略（issue #104）。
 *
 * <p>真实发生过的故障：模型在没有任何数据库工具的会话里被问到业务数据，**编造了一份演示数据、
 * 画成图表，画完才补一句"这是我根据模拟数据推算的"**。产出看起来完全正常，只有读完最后一句
 * 才知道数字是假的——演示时面试官问一句"这数据哪来的"，答案是"编的"。
 *
 * <p>提示词里已经写了"不要凭空推断"，模型照样编。这是 ReAct 不是 Workflow，安全性不能寄托在
 * 模型自觉上：**提示词是软约束，代码是硬边界**，两者都要有，但永远不用前者代替后者。
 *
 * <p>机制放在 {@code loop}（通用），具体名单在装配层给（业务）：这一层不知道什么是图表工具、
 * 什么是 SQL，只知道"某些工具在本次会话产出过数据之前不许调用"。
 *
 * @param consumers 受管的消费型工具名（如各种图表生成工具）
 * @param producers 认可的数据产出工具名（如 {@code execute_sql}/{@code load_file_content}）
 */
public record DataProvenancePolicy(Set<String> consumers, Set<String> producers) {

    /** 两个名单任一为空都等价于不启用——不做半吊子拦截。 */
    public static final DataProvenancePolicy DISABLED = new DataProvenancePolicy(Set.of(), Set.of());

    public DataProvenancePolicy {
        consumers = Set.copyOf(consumers);
        producers = Set.copyOf(producers);
    }

    public boolean guards(String toolName) {
        return !consumers.isEmpty() && !producers.isEmpty() && consumers.contains(toolName);
    }

    /**
     * 本次会话到目前为止有没有真实的数据来源。
     *
     * <p>两条来源都认：① 某个产出型工具成功跑过（失败的不算——失败结果里没有数据）；
     * ② 用户自己把数据贴了进来。后者必须认，否则"我把 Excel 导出的数据粘给你，帮我画个图"
     * 这个完全合法的用法会被一起堵死。
     */
    public boolean satisfiedBy(List<Message> history) {
        return history.stream().anyMatch(message -> producedData(message) || userSuppliedData(message));
    }

    private boolean producedData(Message message) {
        if (!(message instanceof ToolResponseMessage toolResponses)) {
            return false;
        }
        return toolResponses.getResponses().stream()
                .anyMatch(response -> producers.contains(response.name()) && !isFailure(response.responseData()));
    }

    /** 工具约定失败结果以 "Error:" 开头（见各 analytics 工具、限速拒绝文案）。 */
    private static boolean isFailure(String result) {
        return result == null || result.isBlank() || result.stripLeading().startsWith("Error:");
    }

    /**
     * 用户消息里像不像贴了一份数据：Markdown 表格行，或者一行里出现三个以上数字。
     * 判得窄一点——漏判的代价是模型被要求先查一次数据，误判的代价是合法请求被拒绝。
     */
    private static boolean userSuppliedData(Message message) {
        if (message.getMessageType() != MessageType.USER) {
            return false;
        }
        String text = message.getText();
        if (text == null) {
            return false;
        }
        return text.lines().anyMatch(line -> line.chars().filter(character -> character == '|').count() >= 2
                || line.split("\\d+(\\.\\d+)?").length > 3);
    }

    /** 拒绝时给模型的下一步指引——具体到"该先做什么"，让它能自洽改写而不是原地重试。 */
    public String rejectionReason(String toolName) {
        return "Error: %s 需要真实的数据来源，本次会话还没有任何数据产出。".formatted(toolName)
                + "请先调用 " + String.join(" / ", producers) + " 获取数据，"
                + "或者请用户把数据直接贴出来；不要用自己推断的数字作图。";
    }
}
