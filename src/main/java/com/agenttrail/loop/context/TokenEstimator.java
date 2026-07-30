package com.agenttrail.loop.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/**
 * 估算消息列表的 token 数，用于决定要不要触发上下文压缩。
 *
 * <p>刻意不接外部分词库：这个数字只服务于"要不要压缩"这个粗粒度判断，
 * 差个百分之十几完全不影响决策，不值得为此引入一个依赖和它的模型文件。
 *
 * <p>中英文必须分开算——中文单字的信息密度远高于英文字母，用同一个比率会让中文场景
 * 严重低估、压缩迟迟不触发，直到真的撞上模型的上下文窗口才报错。
 */
public final class TokenEstimator {

    /** 英文/ASCII：约 4 字符 = 1 token */
    private static final double CHARS_PER_TOKEN_ASCII = 4.0;

    /** 中日韩字符：约 1.5 字符 = 1 token */
    private static final double CHARS_PER_TOKEN_CJK = 1.5;

    private TokenEstimator() {
    }

    public static int estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        CharCount total = new CharCount();
        for (Message message : messages) {
            total.add(countOf(message));
        }
        return (int) (total.cjk / CHARS_PER_TOKEN_CJK + total.ascii / CHARS_PER_TOKEN_ASCII);
    }

    /**
     * 逐类消息取出所有会进入请求体的文本。
     *
     * <p>工具调用的参数和工具返回值必须算进去——它们往往是整个上下文里最大的一块，
     * 漏算的话压缩永远不会在该触发的时候触发。
     */
    private static CharCount countOf(Message message) {
        CharCount count = new CharCount();
        count.add(message.getText());

        if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                count.add(toolCall.name());
                count.add(toolCall.arguments());
            }
        } else if (message instanceof ToolResponseMessage toolResponses) {
            for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                count.add(response.responseData());
            }
        }
        return count;
    }

    /** 累加中日韩字符数与其余字符数。 */
    private static final class CharCount {

        private int cjk;
        private int ascii;

        void add(String text) {
            if (text == null) {
                return;
            }
            for (int i = 0; i < text.length(); i++) {
                if (isCjk(text.charAt(i))) {
                    cjk++;
                } else {
                    ascii++;
                }
            }
        }

        void add(CharCount other) {
            this.cjk += other.cjk;
            this.ascii += other.ascii;
        }
    }

    /** 覆盖基本汉字、扩展 A、兼容汉字、部首、全角标点与日文假名。 */
    private static boolean isCjk(char ch) {
        return (ch >= '一' && ch <= '鿿')
                || (ch >= '㐀' && ch <= '䶿')
                || (ch >= '豈' && ch <= '﫿')
                || (ch >= '⺀' && ch <= '⻿')
                || (ch >= '　' && ch <= '〿')
                || (ch >= '＀' && ch <= '￯')
                || (ch >= '぀' && ch <= 'ゟ')
                || (ch >= '゠' && ch <= 'ヿ');
    }
}
