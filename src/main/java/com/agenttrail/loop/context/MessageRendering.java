package com.agenttrail.loop.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/**
 * 把消息列表渲染成人类可读文本——工具调用轮把参数一并列出，工具结果轮把返回内容原样附上。
 *
 * <p>最初只是 {@link ContextCompactor} 摘要提示词的一部分，issue #17（TraceAudit）需要同样的
 * 渲染把每轮的输入落成可读记录，于是提出来做共享工具，避免两处各写一份几乎相同的分支判断。
 */
public final class MessageRendering {

    private MessageRendering() {
    }

    public static String render(List<Message> messages) {
        StringBuilder rendered = new StringBuilder();
        for (Message message : messages) {
            rendered.append('[').append(message.getMessageType()).append("] ")
                    .append(textOf(message)).append("\n\n");
        }
        return rendered.toString();
    }

    private static String textOf(Message message) {
        if (message instanceof AssistantMessage assistant) {
            StringBuilder text = new StringBuilder(nullToEmpty(assistant.getText()));
            if (assistant.getToolCalls() != null) {
                text.append(renderToolCalls(assistant.getToolCalls()));
            }
            return text.toString();
        }
        if (message instanceof ToolResponseMessage toolResponses) {
            StringBuilder text = new StringBuilder();
            for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                text.append(nullToEmpty(response.responseData()));
            }
            return text.toString();
        }
        return nullToEmpty(message.getText());
    }

    /**
     * 工具调用列表渲染成人类可读文本，一行一个——{@link com.agenttrail.loop.core.AgentLoopExecutor}
     * 的 TraceAudit（issue #17）记工具调用轮的产出时复用这个方法，避免两处各写一份同样的格式化。
     */
    public static String renderToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        StringBuilder rendered = new StringBuilder();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            rendered.append("[调用工具 ").append(toolCall.name())
                    .append(" 参数=").append(toolCall.arguments()).append(']').append('\n');
        }
        return rendered.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
