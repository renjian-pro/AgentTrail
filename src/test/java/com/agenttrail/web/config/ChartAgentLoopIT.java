package com.agenttrail.web.config;
import com.agenttrail.web.service.AgentLoopExecutorFactory;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #23 验收标准的端到端证明："模型能通过 mcp-echarts 生成图表，图片落进 MinIO，
 * 工具返回给模型（进而返回给用户）的是一段可访问的 URL，不是 base64/二进制"——走完整的
 * V1 ReAct 循环（真实 ChatModel 自己决定要不要调用图表工具），不是直接摆弄
 * {@code ChartToolProvider}（那部分见 {@code ChartToolProviderIT}）。
 *
 * <p>依赖本机已经起了真实 mcp-echarts streamable-HTTP 实例（见
 * {@code ChartToolProviderIT} 类注释里的启动命令），否则 {@code AgentLoopExecutorFactory}
 * 会优雅降级成不带图表工具的执行器，这个测试会因为答案里没有图片 URL 而失败——这是预期行为，
 * 不是这个测试类的 bug。
 */
@SpringBootTest
class ChartAgentLoopIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
        registry.add("spring.datasource.username", SharedMySql::username);
        registry.add("spring.datasource.password", SharedMySql::password);
    }

    @Autowired
    private AgentLoopExecutorFactory executorFactory;

    @Test
    void generatesARealChartAndReturnsAnAccessibleImageUrl() {
        AgentLoopExecutor executor = executorFactory.forModelWithCharts("deepseek-chat", false);

        String answer = executor.call(
                "帮我用图表工具画一个柱状图，展示三个产品的销量对比：A 产品 120，B 产品 85，C 产品 200。"
                        + "生成之后，把图片的访问链接原样发给我，不要省略、不要用 markdown 包裹。",
                new RunnableParams("conv-" + System.nanoTime(), "user-1"));

        assertThat(answer).isNotBlank();
        assertThat(answer).as("回答里应该带一段可访问的图片 URL，而不是拒绝作画或者吐出一堆 base64")
                .containsPattern("https?://\\S+\\.(png|svg)");
        assertThat(answer.length()).as("如果工具结果是 base64 图片数据混进了最终回答，长度会异常大")
                .isLessThan(5_000);
    }
}
