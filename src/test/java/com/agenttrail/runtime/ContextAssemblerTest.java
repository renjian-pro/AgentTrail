package com.agenttrail.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {
    @Test
    void preservesSystemHistoryUserOrder() {
        List<Message> messages = new ContextAssembler().assemble("system", List.of(), "question");

        assertThat(messages).extracting(Message::getMessageType)
                .containsExactly(org.springframework.ai.chat.messages.MessageType.SYSTEM,
                        org.springframework.ai.chat.messages.MessageType.USER);
        assertThat(messages.get(1).getText()).isEqualTo("question");
    }
}
