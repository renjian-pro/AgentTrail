package com.agenttrail.web;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.ppt.JdbcPptTaskStore;
import com.agenttrail.loop.ppt.PptGenerationService;
import com.agenttrail.loop.ppt.PptGenerationStrategy;
import com.agenttrail.loop.ppt.PptPythonRenderer;
import com.agenttrail.loop.ppt.PptTaskStore;
import com.agenttrail.loop.ppt.image.DashScopeImageClient;
import com.agenttrail.loop.ppt.image.MinioPptImageStore;
import com.agenttrail.loop.ppt.image.PptImageStore;
import com.agenttrail.loop.ppt.image.TextToImageClient;
import com.agenttrail.loop.ppt.strategy.ImageStrategy;
import com.agenttrail.loop.ppt.strategy.InitStrategy;
import com.agenttrail.loop.ppt.strategy.OutlineStrategy;
import com.agenttrail.loop.ppt.strategy.RenderStrategy;
import com.agenttrail.loop.ppt.strategy.RequirementStrategy;
import com.agenttrail.loop.ppt.strategy.SchemaStrategy;
import com.agenttrail.loop.ppt.strategy.SearchStrategy;
import com.agenttrail.loop.ppt.strategy.TemplateStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

/**
 * PPT 生成状态机（issue #24）的生产装配。REQUIREMENT/OUTLINE/SCHEMA 三个状态共用同一个不挂
 * 任何工具的 {@link AgentLoopExecutor}（issue #20 多模型路由里已经装配好的执行器，默认
 * {@code deepseek-chat}，和 {@code DeepResearchConfig} 同样的理由——这几步都是纯文本/结构化输出
 * 生成，不需要工具）。
 *
 * <p>Python 渲染脚本路径、模板文件路径、渲染超时时间全部走配置（{@code agenttrail.ppt.*}），
 * 不写死本地绝对路径——issue #24 明确要求避免的第二个反面案例。默认值是相对于项目根目录的
 * 相对路径，和这个项目当前"本机开发、`mvn` 系列命令都从仓库根目录跑"的实际使用方式一致。
 */
@Configuration
public class PptGenerationConfig {

    @Bean
    public PptTaskStore pptTaskStore(DataSource dataSource) {
        return new JdbcPptTaskStore(dataSource);
    }

    @Bean
    public PptPythonRenderer pptPythonRenderer(
            @Value("${agenttrail.ppt.python-executable:python}") String pythonExecutable,
            @Value("${agenttrail.ppt.render-script-path:src/main/resources/ppt-scripts/render_ppt.py}")
            String renderScriptPath,
            @Value("${agenttrail.ppt.render-timeout-seconds:60}") long renderTimeoutSeconds) {
        return new PptPythonRenderer(pythonExecutable, renderScriptPath, renderTimeoutSeconds);
    }

    @Bean
    public InitStrategy pptInitStrategy() {
        return new InitStrategy();
    }

    @Bean
    public RequirementStrategy pptRequirementStrategy(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.ppt.model:deepseek-chat}") String modelId) {
        return new RequirementStrategy(executorFactory.forModel(modelId, false));
    }

    /**
     * SEARCH 状态（issue #29）：必须是挂了联网搜索工具的执行器（{@code forModel(id, true)}），
     * 和 {@code DeepResearchConfig#deepResearchService} 里 {@code searchExecutor} 的装配理由
     * 完全一致——单独用 {@code agenttrail.ppt.model} 而不是复用 {@code agenttrail.deepresearch.model}，
     * 是为了让 PPT 流水线的模型选择只受 PPT 自己的配置项控制，不因为改 DeepResearch 的默认模型
     * 而意外联动到 PPT 的 SEARCH 状态。
     */
    @Bean
    public SearchStrategy pptSearchStrategy(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.ppt.model:deepseek-chat}") String modelId) {
        return new SearchStrategy(executorFactory.forModel(modelId, true));
    }

    @Bean
    public TemplateStrategy pptTemplateStrategy(
            @Value("${agenttrail.ppt.template-path:src/main/resources/ppt-templates/default-template.pptx}")
            String templatePath) {
        return new TemplateStrategy(templatePath);
    }

    @Bean
    public OutlineStrategy pptOutlineStrategy(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.ppt.model:deepseek-chat}") String modelId) {
        return new OutlineStrategy(executorFactory.forModel(modelId, false));
    }

    @Bean
    public SchemaStrategy pptSchemaStrategy(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.ppt.model:deepseek-chat}") String modelId) {
        return new SchemaStrategy(executorFactory.forModel(modelId, false));
    }

    @Bean
    public RenderStrategy pptRenderStrategy(PptPythonRenderer pptPythonRenderer,
            @Value("${agenttrail.ppt.output-dir:target/ppt-output}") String outputDir) {
        return new RenderStrategy(pptPythonRenderer, outputDir);
    }

    /**
     * 文生图 API 客户端（issue #31）——DashScope {@code qwen-image-plus}，直接 REST 调用（接口契约
     * 见 {@link DashScopeImageClient} 类注释），复用同一个 {@code DASHSCOPE_API_KEY}（issue #20
     * 多模型路由/issue #27 图片多模态问答已经在用的同一个 DashScope 账号，不是新申请的 key）。
     */
    @Bean
    public TextToImageClient pptTextToImageClient(
            @Value("${agenttrail.ppt.image.endpoint}") String endpoint,
            @Value("${agenttrail.ppt.image.api-key}") String apiKey,
            @Value("${agenttrail.ppt.image.model}") String model,
            @Value("${agenttrail.ppt.image.size}") String size,
            @Value("${agenttrail.ppt.image.timeout-seconds}") long timeoutSeconds) {
        return new DashScopeImageClient(endpoint, apiKey, model, size, Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * 配图立即转存 MinIO（issue #31）——独立的 {@code agenttrail-ppt-images} bucket，和 issue #23
     * 图表的 {@code agenttrail-charts} bucket 同一个"共享 MinIO 实例、各功能各自一份 bucket"约定，
     * 复用同一套 {@code AGENTTRAIL_MINIO_*} 环境变量（同一个 {@code llmentor-minio} 容器）。
     */
    @Bean
    public PptImageStore pptImageStore(
            @Value("${agenttrail.ppt.image.minio-endpoint}") String endpoint,
            @Value("${agenttrail.ppt.image.minio-access-key}") String accessKey,
            @Value("${agenttrail.ppt.image.minio-secret-key}") String secretKey,
            @Value("${agenttrail.ppt.image.minio-bucket}") String bucket,
            @Value("${agenttrail.ppt.image.timeout-seconds}") long timeoutSeconds) {
        return new MinioPptImageStore(endpoint, accessKey, secretKey, bucket, Duration.ofSeconds(timeoutSeconds));
    }

    @Bean
    public ImageStrategy pptImageStrategy(TextToImageClient pptTextToImageClient, PptImageStore pptImageStore) {
        return new ImageStrategy(pptTextToImageClient, pptImageStore);
    }

    /**
     * Spring 按接口类型把上面全部 {@link PptGenerationStrategy} bean 自动收集成一个
     * {@code List}——不需要在这里手写"哪个状态对应哪个实现"的映射，
     * {@link PptGenerationService} 构造函数自己按 {@code handledState()} 建分发表。
     */
    @Bean
    public PptGenerationService pptGenerationService(PptTaskStore pptTaskStore,
            List<PptGenerationStrategy> pptGenerationStrategies) {
        return new PptGenerationService(pptTaskStore, pptGenerationStrategies);
    }
}
