package com.agenttrail;

import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 冒烟测试：Spring 上下文能否正常装配起来。
 *
 * <p>会话持久化引入 JDBC 之后，应用启动就需要一个真实数据源了。这里连
 * {@link SharedMySql}（开发机上常驻的 MySQL 实例）而不是 H2 —— 和其余集成测试保持
 * 同一条规矩（见 AGENTS.md），否则"上下文能起来"这个结论在真实数据库上未必成立。
 */
@SpringBootTest
class AgentTrailApplicationTests {

	@DynamicPropertySource
	static void datasourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
		registry.add("spring.datasource.username", SharedMySql::username);
		registry.add("spring.datasource.password", SharedMySql::password);
	}

	@Test
	void contextLoads() {
	}

}
