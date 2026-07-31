package com.agenttrail.loop.multimodal;

import com.agenttrail.loop.multimodal.support.RecordingSyncChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

class ImageDescriptionServiceTest {

    @Test
    void returnsTheModelsDescriptionForANonEmptyImage() {
        RecordingSyncChatModel chatModel = new RecordingSyncChatModel(text("一只猫坐在窗台上"));
        ImageDescriptionService service = new ImageDescriptionService(chatModel, "qwen3-vl-plus");

        String description = service.describe(new byte[]{1, 2, 3}, "cat.png");

        assertThat(description).isEqualTo("一只猫坐在窗台上");
    }

    @Test
    void callsWithTheConfiguredModelNameRegardlessOfTheChatModelsOwnDefault() {
        RecordingSyncChatModel chatModel = new RecordingSyncChatModel(text("description"));
        ImageDescriptionService service = new ImageDescriptionService(chatModel, "qwen3-vl-plus");

        service.describe(new byte[]{1, 2, 3}, "photo.jpg");

        Prompt sentPrompt = chatModel.recordedPrompts().get(0);
        assertThat(((OpenAiChatOptions) sentPrompt.getOptions()).getModel()).isEqualTo("qwen3-vl-plus");
    }

    @Test
    void returnsAPlaceholderForEmptyImageBytesWithoutCallingTheModel() {
        RecordingSyncChatModel chatModel = new RecordingSyncChatModel();
        ImageDescriptionService service = new ImageDescriptionService(chatModel, "qwen3-vl-plus");

        String description = service.describe(new byte[0], "empty.png");

        assertThat(description).contains("为空");
        assertThat(chatModel.callCount()).isZero();
    }

    @Test
    void returnsAPlaceholderWhenTheModelRespondsWithBlankText() {
        RecordingSyncChatModel chatModel = new RecordingSyncChatModel(text(""));
        ImageDescriptionService service = new ImageDescriptionService(chatModel, "qwen3-vl-plus");

        String description = service.describe(new byte[]{1}, "photo.png");

        assertThat(description).contains("无法识别");
    }
}
