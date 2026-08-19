package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptContentSlideFill;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptRequirement;
import com.agenttrail.capability.ppt.PptSchema;
import com.agenttrail.capability.ppt.image.PptImageStore;
import com.agenttrail.support.LocalConfig;
import com.agenttrail.support.SharedMySql;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #31 验收标准第三条："用真实的文生图 API key 和真实的 MinIO 实例跑通一次'生图→立即转存→
 * PPT 里正确引用'的完整流程"——真实 DashScope {@code qwen-image-plus}（本地 YAML 中的
 * Spring AI OpenAI 兼容配置，issue #20/#27 同一个账号）+ 真实
 * 本机 MinIO 容器，不 mock 任何一环。连接地址和凭据只从本地配置读取。
 *
 * <p>只单独跑 {@link ImageStrategy} 这一个状态（而不是走 {@code PptGenerationServiceIT} 那种
 * 8 状态端到端），是为了精确断言"配图→转存→schema 引用"这条链路本身，不被前面几个状态
 * （REQUIREMENT/SEARCH/OUTLINE/SCHEMA 各自的真实 LLM 调用）偶发的失败或耗时盖住——那几步已经由
 * {@code PptGenerationServiceIT}/{@code SearchStrategyIT} 各自覆盖过。这里手写一份已经跑完
 * SCHEMA 状态的上下文作为输入，和 {@code SchemaStrategyTest} 手写 outline 输入是同一个道理。
 */
@SpringBootTest
class ImageStrategyIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
        registry.add("spring.datasource.username", SharedMySql::username);
        registry.add("spring.datasource.password", SharedMySql::password);
    }

    private static final String MINIO_ENDPOINT = LocalConfig.require("agenttrail.minio.endpoint");
    private static final String MINIO_ACCESS_KEY = LocalConfig.require("agenttrail.minio.access-key");
    private static final String MINIO_SECRET_KEY = LocalConfig.require("agenttrail.minio.secret-key");
    private static final String MINIO_BUCKET = LocalConfig.require("agenttrail.ppt.image.minio-bucket");

    @Autowired
    private ImageStrategy imageStrategy;

    @Autowired
    private PptImageStore imageStore;

    @Test
    void generatesARealImageDownloadsAndReuploadsToMinioAndTheSchemaReferencesTheStableObjectKey() throws Exception {
        PptSchema schemaBeforeImage = new PptSchema("Spring AI Agent 实战", "面向团队内部分享",
                List.of(new PptContentSlideFill("为什么手写 ReAct Loop", "绕开 ChatClient 差异")));
        PptGenerationContext context = PptGenerationContext
                .initial("it-image-" + System.currentTimeMillis(), "帮我做一份介绍 PPT")
                .withRequirement(new PptRequirement("Spring AI Agent 实战", "Spring AI Agent 框架",
                        "Java 后端工程师", 2, "专业简洁"))
                .withSchema(schemaBeforeImage);

        PptGenerationContext result = imageStrategy.execute(context);

        String storedReference = result.schema().coverImageUrl();
        assertThat(storedReference)
                .as("配图成功时 schema 必须真的多出一个 coverImageUrl——失败时才允许是 null，" +
                        "如果这个断言失败，很可能是 spring.ai.openai.api-key 没配或者 MinIO 没起，不是代码逻辑错")
                .isNotBlank();
        assertThat(storedReference)
                .as("checkpoint 必须保存稳定 MinIO object key，不能保存 DashScope 或短时签名 URL")
                .doesNotStartWith("http://")
                .doesNotStartWith("https://")
                .doesNotContain("dashscope", "X-Amz-Signature");

        // schema 原有的文字字段必须原样保留——IMAGE 状态只应该补上 coverImageUrl 这一个字段
        assertThat(result.schema().titleText()).isEqualTo("Spring AI Agent 实战");
        assertThat(result.schema().contentSlides()).hasSize(1);

        // 1) 不能只信任返回的 URL 字符串本身——用 MinIO 官方 SDK 独立核实一遍，图片真的落进了
        //    配置好的 agenttrail-ppt-images bucket，和 issue #23 ChartToolProviderIT 同一个道理
        MinioClient minioClient = MinioClient.builder()
                .endpoint(MINIO_ENDPOINT)
                .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
                .build();
        assertThat(minioClient.statObject(StatObjectArgs.builder()
                        .bucket(MINIO_BUCKET)
                        .object(storedReference)
                        .build()))
                .as("MinIO 里必须真的存在这个对象").isNotNull();

        // 2) 渲染时才解析短时签名 URL；既验证私有 bucket 可读，也避免把会过期的 URL 持久化。
        URI imageUri = URI.create(imageStore.resolveForRender(storedReference));
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(imageUri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(contentType -> assertThat(contentType).startsWith("image/"));
        assertThat(response.body()).as("转存到 MinIO 的图片内容不应该是空的").isNotEmpty();
    }
}
