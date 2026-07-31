package com.agenttrail.web;

import com.agenttrail.loop.deepresearch.DeepResearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepResearch（issue #25/#34/#36）的生产装配。默认模型是 {@code deepseek-chat}，不是 issue #20
 * 的默认 {@code qwen-plus}——DeepResearch 的执行阶段必须真的调用联网搜索工具，而 issue #22 联调时
 * 发现 qwen-plus 走 {@code spring-ai-openai:2.0.0} 合并流式 tool_call chunk 有个未修的第三方库
 * 兼容性问题（见踩坑点 #78a），deepseek-chat 不受影响，DeepResearch 不能默认踩这个坑。
 */
@Configuration
public class DeepResearchConfig {

    @Bean
    public DeepResearchService deepResearchService(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.deepresearch.model:deepseek-chat}") String modelId,
            @Value("${agenttrail.deepresearch.max-concurrent-tasks-per-layer:3}") int maxConcurrentTasksPerLayer,
            @Value("${agenttrail.deepresearch.max-tasks-per-plan:20}") int maxTasksPerPlan,
            @Value("${agenttrail.deepresearch.max-task-retries:2}") int maxTaskRetries) {
        return new DeepResearchService(
                executorFactory.forModel(modelId, false),
                executorFactory.forModel(modelId, true),
                maxConcurrentTasksPerLayer, maxTasksPerPlan, maxTaskRetries);
    }
}
