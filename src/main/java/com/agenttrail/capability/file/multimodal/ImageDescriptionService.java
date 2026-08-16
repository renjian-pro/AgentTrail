package com.agenttrail.capability.file.multimodal;

import com.agenttrail.loop.core.SynchronousLlmCall;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.Locale;

/**
 * 图片 → 文本描述（issue #27）。用同一个 {@code openAiChatModel} bean（issue #20 默认对话模型
 * 那个），但按 {@link Prompt} 级别的 {@link OpenAiChatOptions} 把模型名换成多模态模型
 * （默认 {@code qwen3-vl-plus}）——不需要像 issue #20 的按会话模型路由那样单独建一个
 * {@code ChatModel} bean/走 {@code OpenAIClient} 手工装配，一次性的图片描述调用没有
 * 复用 {@code AgentLoopExecutor} 的必要，Prompt 级别覆盖模型名足够。
 *
 * <p>是否懒加载、结果写回缓存不在这里管——那是 {@link com.agenttrail.capability.file.FileQaService}
 * 编排的事，这里只负责"给字节，吐描述"这一件事。
 */
public class ImageDescriptionService {

    private static final String DESCRIBE_PROMPT =
            "请精简且全面地描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，"
                    + "直接输出纯文本描述，不要多余解释和说明，不要使用换行符或特殊符号";
    private static final String EMPTY_IMAGE_NOTICE = "[图片内容为空]";
    private static final String UNRECOGNIZABLE_NOTICE = "[无法识别图片内容]";

    private final ChatModel visionCapableChatModel;
    private final String model;

    public ImageDescriptionService(ChatModel visionCapableChatModel, String model) {
        this.visionCapableChatModel = visionCapableChatModel;
        this.model = model;
    }

    /**
     * 带着用户的具体问题重新看原图（issue #105）。
     *
     * <p>和 {@link #describe} 的区别不是措辞，是**信息量**：describe 产出的是一次性的通用描述，
     * 缓存下来之后主对话模型看到的永远是那段文字。用户追问"图里左下角那个数字是多少"时，
     * 描述里没覆盖就答不出——而且模型不知道自己没看到原图，会编。这条路每次都重新看图。
     *
     * <p>和"大文件带着问题走 RAG 检索"是同一个模式：不预先把全部内容塞进上下文，
     * 而是带着真实问题按需取。主对话模型因此不需要换成视觉模型——那会和 tool calling 冲突
     * （带工具的请求已经被强制路由到不带视觉的 deepseek-chat，见踩坑点 #78a）。
     */
    public String answerAbout(byte[] imageBytes, String fileName, String question) {
        if (question == null || question.isBlank()) {
            return describe(imageBytes, fileName);
        }
        return callVisionModel(imageBytes, fileName,
                "请只根据这张图片回答下面的问题，答不出就直说图中没有这个信息，不要推测：" + question.strip());
    }

    public String describe(byte[] imageBytes, String fileName) {
        return callVisionModel(imageBytes, fileName, DESCRIBE_PROMPT);
    }

    private String callVisionModel(byte[] imageBytes, String fileName, String instruction) {
        if (imageBytes == null || imageBytes.length == 0) {
            return EMPTY_IMAGE_NOTICE;
        }

        UserMessage message = UserMessage.builder()
                .text(instruction)
                .media(new Media(detectMimeType(fileName), new ByteArrayResource(imageBytes)))
                .build();
        Prompt prompt = new Prompt(List.of(message), OpenAiChatOptions.builder().model(model).build());

        ChatResponse response = SynchronousLlmCall.call(visionCapableChatModel, prompt);
        String text = response.getResult().getOutput().getText();
        return (text == null || text.isBlank()) ? UNRECOGNIZABLE_NOTICE : text.trim();
    }

    private static MimeType detectMimeType(String fileName) {
        if (fileName != null) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png")) {
                return MimeTypeUtils.IMAGE_PNG;
            }
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return MimeTypeUtils.IMAGE_JPEG;
            }
            if (lower.endsWith(".gif")) {
                return MimeTypeUtils.IMAGE_GIF;
            }
            if (lower.endsWith(".bmp")) {
                return MimeType.valueOf("image/bmp");
            }
            if (lower.endsWith(".webp")) {
                return MimeType.valueOf("image/webp");
            }
        }
        return MimeTypeUtils.IMAGE_JPEG;
    }
}
