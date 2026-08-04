package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试：模型没有别的途径知道"今天"是哪天。曾经这里完全不注入日期，实测会导致模型
 * 要么去猜一个不存在的 fileId 调用 load_file_content，要么编一个听起来合理但纯属瞎猜的
 * 日期（还在同一句话里自称"无法获取当前时间"）。日期消息必须无条件注入，不像记忆/文件
 * 区块那样有"未启用就不插入"的情况。
 */
class AgentLoopExecutorDateTest {

    @Test
    void injectsTodaysDateAsTheLeadingSystemMessageOnEveryTurn() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("你好")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5);

        executor.stream("你好", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        Message first = chatModel.messagesAtRound(0).get(0);
        assertThat(first).isInstanceOf(SystemMessage.class);
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日"));
        assertThat(first.getText())
                .contains(today)
                .contains("不要说自己无法获取当前时间")
                .contains("不要编造另一个日期");
    }
}
