package com.agenttrail.loop.prompt;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #100。这个测试守的是"提示词改动可归因"这条链的完整性——不是格式洁癖：
 * 提示词悄悄改了而版本号没动，Golden 分数的变化就会归因到旧版本，那是评测体系里最难查的一类错，
 * 因为一切看起来都正常。
 */
class PromptRegistryTest {

    private static final PromptRegistry REGISTRY = PromptRegistry.loadFromClasspath();

    @SuppressWarnings("unchecked")
    private static Map<String, String> lockfile() {
        try (InputStream input = PromptRegistryTest.class.getResourceAsStream("/prompts/versions.lock.yml")) {
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            return (Map<String, String>) root.get("prompts");
        } catch (Exception failure) {
            throw new IllegalStateException("读不到 prompts/versions.lock.yml", failure);
        }
    }

    /**
     * 失败时照着提示改：正文改了就把 front matter 的 version 加一档，然后把 lockfile 里那一行
     * 换成报错信息里的"实际"值。别反过来只改 lockfile——那等于把归因能力关掉。
     */
    @Test
    void everyPromptMatchesTheVersionLock() {
        assertThat(REGISTRY.verifyAgainst(lockfile()))
                .as("提示词与 versions.lock.yml 不一致")
                .isEmpty();
    }

    @Test
    void loadsEveryExternalisedPrompt() {
        assertThat(REGISTRY.all()).extracting(PromptDefinition::id)
                .contains("deepresearch.clarification", "deepresearch.plan", "ppt.outline", "ppt.schema",
                        "runtime.context_compaction", "runtime.memory_extraction", "runtime.llm_judge");
    }

    /** 正文不能是空的——空提示词会让模型完全失去指令，而且不会报错，是典型的静默失败。 */
    @Test
    void refusesToServeAnEmptyPrompt() {
        assertThat(REGISTRY.all()).allSatisfy(definition ->
                assertThat(definition.text()).as(definition.id()).isNotBlank());
    }

    /** trace 要靠这个字符串归因，三段缺一不可。 */
    @Test
    void stampsCarryIdVersionAndHash() {
        PromptDefinition definition = REGISTRY.get("ppt.outline");
        assertThat(definition.stamp()).isEqualTo("ppt.outline@" + definition.version() + "#" + definition.hash());
        assertThat(definition.hash()).hasSize(8);
    }

    /** 未知 id 直接抛：提示词缺失是装配错误，静默返回空串会让模型在没有指令的情况下继续跑。 */
    @Test
    void failsLoudlyOnAnUnknownPrompt() {
        assertThat(REGISTRY.all()).extracting(PromptDefinition::id).doesNotContain("nope.missing");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> REGISTRY.get("nope.missing"))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("nope.missing");
    }

    /** 版本号没动、正文变了 —— 这正是要抓的那一种，必须报出来。 */
    @Test
    void reportsBodyChangedWithoutAVersionBump() {
        Map<String, String> tampered = new java.util.HashMap<>(lockfile());
        String id = "ppt.outline";
        tampered.put(id, REGISTRY.get(id).version() + "#deadbeef");

        List<String> problems = REGISTRY.verifyAgainst(tampered);

        assertThat(problems).anySatisfy(problem ->
                assertThat(problem).contains(id).contains("版本号没动"));
    }
}
