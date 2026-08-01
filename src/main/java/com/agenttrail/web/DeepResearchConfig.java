package com.agenttrail.web;

import com.agenttrail.loop.context.ContextCompactor;
import com.agenttrail.loop.context.ContextPolicy;
import com.agenttrail.loop.deepresearch.DeepResearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepResearch（issue #25/#34/#35/#36/#37）的生产装配。默认模型是 {@code deepseek-chat}，不是
 * issue #20 的默认 {@code qwen-plus}——DeepResearch 的执行阶段必须真的调用联网搜索工具，而 issue #22
 * 联调时发现 qwen-plus 走 {@code spring-ai-openai:2.0.0} 合并流式 tool_call chunk 有个未修的第三方库
 * 兼容性问题（见踩坑点 #78a），deepseek-chat 不受影响，DeepResearch 不能默认踩这个坑。
 */
@Configuration
public class DeepResearchConfig {

    @Bean
    public DeepResearchService deepResearchService(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.deepresearch.model:deepseek-chat}") String modelId,
            @Value("${agenttrail.deepresearch.max-concurrent-tasks-per-layer:3}") int maxConcurrentTasksPerLayer,
            @Value("${agenttrail.deepresearch.max-tasks-per-plan:20}") int maxTasksPerPlan,
            @Value("${agenttrail.deepresearch.max-task-retries:2}") int maxTaskRetries,
            @Value("${agenttrail.deepresearch.max-critique-rounds:3}") int maxCritiqueRounds,
            // 默认沿用 ContextPolicy.DEFAULT_TOKEN_THRESHOLD（60_000）——研究上下文和
            // AgentLoopExecutor 自己单轮 ReAct 历史的膨胀量级类似，没有理由默认给一个不同的阈值
            @Value("${agenttrail.deepresearch.context.token-threshold:60000}") int contextTokenThreshold) {
        // DeepResearch 专用的上下文压缩器（issue #37）——只压缩 critique()/summarize() 用到的
        // 累积检索结果 + 批判反馈这份文本，跟 executorFactory.forModel(...) 返回的执行器各自
        // 内部 ReAct 子循环的上下文压缩是两回事，互不干扰、各自独立配置。
        ContextPolicy researchContextPolicy = ContextPolicy.builder()
                .tokenThreshold(contextTokenThreshold)
                .retainLatestOnlyMarkers(DeepResearchService.CRITIQUE_FEEDBACK_MARKER)
                .build();
        ContextCompactor researchContextCompactor = new ContextCompactor(
                researchContextPolicy, executorFactory.chatModelFor(modelId));

        return new DeepResearchService(
                executorFactory.forModel(modelId, false),
                executorFactory.forModel(modelId, true),
                maxConcurrentTasksPerLayer, maxTasksPerPlan, maxTaskRetries, maxCritiqueRounds,
                researchContextCompactor);
    }
}
