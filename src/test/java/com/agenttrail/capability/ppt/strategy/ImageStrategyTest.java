package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptContentSlideFill;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptRequirement;
import com.agenttrail.capability.ppt.PptSchema;
import com.agenttrail.capability.ppt.image.PptImageException;
import com.agenttrail.capability.ppt.image.PptImageStore;
import com.agenttrail.capability.ppt.image.TextToImageClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #31 的核心编排逻辑：{@link ImageStrategy} 只需要证明两件事——成功路径下，持久化进
 * {@link PptSchema#coverImageUrl()} 的是 {@link PptImageStore} 转存之后的 MinIO URL，不是
 * {@link TextToImageClient} 直接返回的临时链接；失败路径下（不管是调文生图 API 失败还是下载/
 * 转存 MinIO 失败），整个方法必须正常返回、不抛异常，不能拖垮整条 PPT 生成流水线（验收标准第四
 * 条）。真实 DashScope + 真实 MinIO 的端到端验证见 {@code ImageStrategyIT}。
 */
class ImageStrategyTest {

    private static PptGenerationContext contextWithSchema() {
        PptSchema schema = new PptSchema("封面标题", "封面副标题",
                List.of(new PptContentSlideFill("内容页标题", "内容页正文")));
        return PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT")
                .withRequirement(new PptRequirement("标题", "Spring AI Agent", "团队内部", 2, "专业简洁"))
                .withSchema(schema);
    }

    @Test
    void persistsTheMinioUrlReturnedByTheImageStoreNotTheTemporaryApiUrl() {
        FakeTextToImageClient imageClient = new FakeTextToImageClient("https://dashscope-temp.example.com/a.png");
        FakeImageStore imageStore = new FakeImageStore("http://localhost:9000/agenttrail-ppt-images/a-uuid.png");
        ImageStrategy strategy = new ImageStrategy(imageClient, imageStore);

        PptGenerationContext result = strategy.execute(contextWithSchema());

        assertThat(result.schema().coverImageUrl())
                .as("持久化到 schema 里的必须是 MinIO 转存之后的 URL")
                .isEqualTo("http://localhost:9000/agenttrail-ppt-images/a-uuid.png");
        assertThat(imageStore.receivedTemporaryUrl)
                .as("转存时传给 PptImageStore 的必须是文生图 API 返回的那个临时链接")
                .isEqualTo("https://dashscope-temp.example.com/a.png");
        // 除了 coverImageUrl，SCHEMA 状态原有的文字字段必须原样保留，不能被这个状态误改
        assertThat(result.schema().titleText()).isEqualTo("封面标题");
        assertThat(result.schema().contentSlides()).hasSize(1);
    }

    @Test
    void passesTheConversationIdAsTheObjectKeyPrefix() {
        FakeTextToImageClient imageClient = new FakeTextToImageClient("https://dashscope-temp.example.com/a.png");
        FakeImageStore imageStore = new FakeImageStore("http://localhost:9000/agenttrail-ppt-images/a-uuid.png");
        ImageStrategy strategy = new ImageStrategy(imageClient, imageStore);

        strategy.execute(contextWithSchema());

        assertThat(imageStore.receivedPrefix).isEqualTo("conv-1");
    }

    @Test
    void groundsThePromptInTheRequirementTopicAndAudience() {
        FakeTextToImageClient imageClient = new FakeTextToImageClient("https://dashscope-temp.example.com/a.png");
        FakeImageStore imageStore = new FakeImageStore("http://localhost:9000/agenttrail-ppt-images/a-uuid.png");
        ImageStrategy strategy = new ImageStrategy(imageClient, imageStore);

        strategy.execute(contextWithSchema());

        assertThat(imageClient.receivedPrompt)
                .contains("Spring AI Agent", "团队内部")
                .contains("16:9", "无文字", "构图");
    }

    @Test
    void degradesGracefullyWithoutThrowingWhenTheTextToImageApiFails() {
        TextToImageClient failingClient = prompt -> {
            throw new PptImageException("DashScope 挂了（测试用）");
        };
        FakeImageStore imageStore = new FakeImageStore("http://localhost:9000/agenttrail-ppt-images/unused.png");
        ImageStrategy strategy = new ImageStrategy(failingClient, imageStore);

        PptGenerationContext result = strategy.execute(contextWithSchema());

        assertThat(result.schema().coverImageUrl())
                .as("配图失败必须降级为无封面图，不能让状态机异常中断").isNull();
        assertThat(imageStore.callCount.get())
                .as("文生图 API 都没调成功，不应该走到转存 MinIO 这一步").isZero();
    }

    @Test
    void degradesGracefullyWithoutThrowingWhenDownloadOrMinioUploadFails() {
        FakeTextToImageClient imageClient = new FakeTextToImageClient("https://dashscope-temp.example.com/a.png");
        PptImageStore failingStore = (url, prefix) -> {
            throw new PptImageException("下载或上传 MinIO 失败（测试用）");
        };
        ImageStrategy strategy = new ImageStrategy(imageClient, failingStore);

        PptGenerationContext result = strategy.execute(contextWithSchema());

        assertThat(result.schema().coverImageUrl())
                .as("下载/转存失败必须降级为无封面图，不能让状态机异常中断").isNull();
        // 降级路径下，schema 的其余字段（尤其是已经产出的文字内容）不应该被这次失败的尝试污染
        assertThat(result.schema().titleText()).isEqualTo("封面标题");
    }

    private static final class FakeTextToImageClient implements TextToImageClient {
        private final String urlToReturn;
        private String receivedPrompt;

        private FakeTextToImageClient(String urlToReturn) {
            this.urlToReturn = urlToReturn;
        }

        @Override
        public String generateImageUrl(String prompt) {
            this.receivedPrompt = prompt;
            return urlToReturn;
        }
    }

    private static final class FakeImageStore implements PptImageStore {
        private final String urlToReturn;
        private final AtomicInteger callCount = new AtomicInteger();
        private String receivedTemporaryUrl;
        private String receivedPrefix;

        private FakeImageStore(String urlToReturn) {
            this.urlToReturn = urlToReturn;
        }

        @Override
        public String downloadAndStore(String temporaryImageUrl, String objectKeyPrefix) {
            callCount.incrementAndGet();
            this.receivedTemporaryUrl = temporaryImageUrl;
            this.receivedPrefix = objectKeyPrefix;
            return urlToReturn;
        }
    }
}
