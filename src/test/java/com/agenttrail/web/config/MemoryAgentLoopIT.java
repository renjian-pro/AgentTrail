package com.agenttrail.web.config;
import com.agenttrail.web.service.AgentLoopExecutorFactory;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只验证一件事：{@code agenttrail.memory.enabled=true} 时，Spring 真的把 {@code memoryStore}
 * 注入进了生产 {@link AgentLoopExecutorFactory} bean——不是像 {@code AgentLoopExecutorMemoryTest}
 * 那样直接用 {@link AgentLoopExecutor.Builder} 装配，而是走真实的
 * {@code AgentLoopExecutorConfig}/{@code AgentLoopExecutorFactory} 生产装配路径 + 真实模型。
 * 机制本身的正确性（记忆注入/提取的各种分支）已经有单测覆盖，这里不重复。
 */
@SpringBootTest(properties = "agenttrail.memory.enabled=true")
class MemoryAgentLoopIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
        registry.add("spring.datasource.username", SharedMySql::username);
        registry.add("spring.datasource.password", SharedMySql::password);
    }

    @Autowired
    private AgentLoopExecutorFactory executorFactory;

    @Autowired
    @Qualifier("dataSource")
    private DataSource dataSource;

    @Test
    void aRealTurnThroughTheProductionFactoryExtractsAndPersistsMemory() {
        String userId = "memory-it-user-" + System.nanoTime();
        AgentLoopExecutor executor = executorFactory.forModel("deepseek-chat");

        String answer = executor.call(
                "我是一名产品经理，请记住这一点，简单回复收到即可。",
                new RunnableParams("conv-" + System.nanoTime(), userId));

        assertThat(answer).isNotBlank();

        List<String> memoryContents = JdbcClient.create(dataSource)
                .sql("SELECT content FROM agent_memory WHERE user_id = ?")
                .param(userId)
                .query(String.class)
                .list();
        assertThat(memoryContents).as("这一轮结束后应该真的触发了一次记忆提取并落库").isNotEmpty();
    }
}
