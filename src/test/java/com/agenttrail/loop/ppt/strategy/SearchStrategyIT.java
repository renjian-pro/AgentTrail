package com.agenttrail.loop.ppt.strategy;

import com.agenttrail.loop.ppt.PptGenerationContext;
import com.agenttrail.loop.ppt.PptRequirement;
import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #29 验收标准："用真实的联网搜索跑通一次'给一个 PPT 主题 → 收集到真实资料 → 资料进入
 * 后续 OUTLINE 阶段'的流程"——本地 YAML 中的真实 Tavily key + 真实
 * {@code deepseek-chat} 模型 key，不 mock 任何一环。
 *
 * <p>7 个状态端到端跑通已经由 {@code PptGenerationServiceIT} 覆盖（issue #24），并且从这一票起
 * 那份测试也顺带验证了真实联网搜索——{@link SearchStrategy} 现在总是真实调用 Tavily，不再有
 * canned 分支可选。这里单独只跑 SEARCH→OUTLINE 两个状态，是为了精确断言"SEARCH 真的收集到了
 * 素材，且这份素材真的被 OUTLINE 用上了"这件事本身，不被端到端流程里其他状态（模板/渲染）
 * 偶发的失败盖住，也不需要真的跑一遍 Python 渲染子进程这么重的前置条件。
 */
@SpringBootTest
class SearchStrategyIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
        registry.add("spring.datasource.username", SharedMySql::username);
        registry.add("spring.datasource.password", SharedMySql::password);
    }

    @Autowired
    private SearchStrategy searchStrategy;

    @Autowired
    private OutlineStrategy outlineStrategy;

    @Test
    void realWebSearchCollectsMaterialThatFlowsIntoTheOutlineStage() {
        PptGenerationContext afterRequirement = PptGenerationContext
                .initial("it-search-" + System.currentTimeMillis(), "帮我做一份介绍 Spring AI 框架核心特性的 PPT")
                .withRequirement(new PptRequirement("Spring AI 框架介绍", "Spring AI 框架的核心特性",
                        "Java 后端工程师", 3, "专业简洁"));

        PptGenerationContext afterSearch = searchStrategy.execute(afterRequirement);

        assertThat(afterSearch.searchMaterials())
                .as("SEARCH 状态应该真的收集到素材，不再是占位输入").isNotEmpty();
        assertThat(afterSearch.searchMaterials())
                .as("每条素材都应该是真实调用产出的非空文本")
                .allSatisfy(material -> assertThat(material).isNotBlank());

        PptGenerationContext afterOutline = outlineStrategy.execute(afterSearch);

        assertThat(afterOutline.outline())
                .as("SEARCH 收集到的素材应该真的流入 OUTLINE 状态，产出一份大纲").isNotNull();
        assertThat(afterOutline.outline().deckTitle()).isNotBlank();
        assertThat(afterOutline.outline().slides()).isNotEmpty();
        assertThat(afterOutline.outline().slides())
                .allSatisfy(slide -> assertThat(slide.bullets()).isNotEmpty());
    }
}
