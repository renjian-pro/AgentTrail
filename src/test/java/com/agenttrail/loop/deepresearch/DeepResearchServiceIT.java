package com.agenttrail.loop.deepresearch;

import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #25 验收标准："用真实的模型 key 和真实的联网搜索，端到端跑通一次完整的
 * 需求澄清→研究→报告流程"。问一个信息充分、需要真实联网搜索才能回答准确的问题，
 * 断言完整跑完了澄清（判定通过）→主题→任务执行（真实调用 Tavily）→报告这条链路。
 */
@SpringBootTest
class DeepResearchServiceIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
        registry.add("spring.datasource.username", SharedMySql::username);
        registry.add("spring.datasource.password", SharedMySql::password);
    }

    @Autowired
    private DeepResearchService deepResearchService;

    @Test
    void runsAFullClarifyResearchReportCycleWithRealSearch() {
        DeepResearchReport report = deepResearchService.research(
                "请研究一下 Spring AI 框架的核心特性和最新进展，输出一份分析报告");

        assertThat(report.needsClarification())
                .as("研究对象和主题都很明确，不该触发澄清").isFalse();
        assertThat(report.researchTopic()).isNotBlank();
        assertThat(report.taskResults()).as("至少应该生成并执行一个搜索任务").isNotEmpty();
        assertThat(report.taskResults()).allSatisfy(result -> assertThat(result.output()).isNotBlank());
        assertThat(report.report()).isNotBlank();
    }

    @Test
    void asksForClarificationWhenTheQuestionIsTooVague() {
        DeepResearchReport report = deepResearchService.research("随便research一下");

        assertThat(report.needsClarification()).isTrue();
        assertThat(report.clarifyingQuestion()).isNotBlank();
        assertThat(report.taskResults()).isEmpty();
    }
}
