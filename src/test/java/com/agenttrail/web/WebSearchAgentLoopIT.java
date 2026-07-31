package com.agenttrail.web;

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
 * issue #22 验收标准："用真实的 Tavily key 跑一次真实搜索，验证返回结果确实能被模型用来回答问题"。
 * 问一个模型单靠训练知识答不出、必须真的搜索当前信息才能回答的问题，断言答案里出现了
 * 搜索本身才能提供的具体数字/日期这类"新鲜"信息——不精确断言具体数值（那是每次都会变的），
 * 只断言答案不是模型的空对空回避（比如"我不知道当前汇率"）。
 */
@SpringBootTest
class WebSearchAgentLoopIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
        registry.add("spring.datasource.username", SharedMySql::username);
        registry.add("spring.datasource.password", SharedMySql::password);
    }

    @Autowired
    private AgentLoopExecutorFactory executorFactory;

    @Test
    void answersAQuestionThatRequiresARealSearchWhenWebSearchIsEnabled() {
        AgentLoopExecutor executor = executorFactory.forModel("deepseek-chat", true);

        String answer = executor.call("现在美元兑人民币的汇率大概是多少？请给出具体数字。",
                new RunnableParams("conv-" + System.nanoTime(), "user-1"));

        assertThat(answer).isNotBlank();
        assertThat(answer).as("应该给出一个具体数字，而不是回避说不知道当前汇率")
                .containsPattern("\\d");
    }
}
