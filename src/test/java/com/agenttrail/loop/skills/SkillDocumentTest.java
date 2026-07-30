package com.agenttrail.loop.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL.md 的 frontmatter 解析。
 *
 * <p>这里的每条断言都对应一个"参考实现会解析错"的输入——技能文件是运营写的 Markdown，
 * 不是程序生成的结构化数据，容错必须在解析这一层做掉，不能指望上游写得规范。
 */
class SkillDocumentTest {

    @Test
    void extractsNameAndDescriptionFromFrontmatter() {
        SkillDocument document = SkillDocument.parse("""
                ---
                name: pptx
                description: 生成演示文稿
                ---

                # PPT 助手
                正文内容
                """);

        assertThat(document.value("name")).isEqualTo("pptx");
        assertThat(document.value("description")).isEqualTo("生成演示文稿");
        assertThat(document.body()).startsWith("# PPT 助手").contains("正文内容");
    }

    @Test
    void stripsSurroundingQuotesFromValues() {
        SkillDocument document = SkillDocument.parse("""
                ---
                name: "pptx"
                description: '带引号的描述'
                ---
                body
                """);

        assertThat(document.value("name")).isEqualTo("pptx");
        assertThat(document.value("description")).isEqualTo("带引号的描述");
    }

    /**
     * 参考实现用 {@code indexOf("---", 3)} 找结束分隔符，值里出现 {@code ---} 就会被从中间截断，
     * 后半截当成正文。这里按"整行等于 ---"来判定，值里的 --- 不再是分隔符。
     */
    @Test
    void treatsOnlyAWholeLineAsTheClosingDelimiter() {
        SkillDocument document = SkillDocument.parse("""
                ---
                name: dash-lover
                description: 先做 A --- 再做 B
                ---
                正文
                """);

        assertThat(document.value("description")).isEqualTo("先做 A --- 再做 B");
        assertThat(document.body()).isEqualTo("正文");
    }

    /** 正文里的 Markdown 分隔线在 frontmatter 闭合之后，不影响解析。 */
    @Test
    void keepsHorizontalRulesInsideTheBody() {
        SkillDocument document = SkillDocument.parse("""
                ---
                name: ruler
                ---
                第一节

                ---

                第二节
                """);

        assertThat(document.value("name")).isEqualTo("ruler");
        assertThat(document.body()).contains("第一节").contains("---").contains("第二节");
    }

    /**
     * key 的顺序必须稳定。工具描述是提示词前缀的一部分，顺序抖动会让每次请求的前缀都不一样，
     * 直接废掉 prompt 缓存——参考实现用 HashMap 存 frontmatter，输出顺序由 hash 决定。
     */
    @Test
    void preservesFrontmatterKeyOrder() {
        SkillDocument document = SkillDocument.parse("""
                ---
                zeta: 1
                alpha: 2
                middle: 3
                ---
                body
                """);

        assertThat(List.copyOf(document.frontmatter().keySet())).containsExactly("zeta", "alpha", "middle");
    }

    @Test
    void ignoresBlankLinesAndComments() {
        SkillDocument document = SkillDocument.parse("""
                ---
                # 这是注释
                name: commented

                description: 有空行
                ---
                body
                """);

        assertThat(document.frontmatter()).containsOnlyKeys("name", "description");
    }

    @Test
    void treatsDocumentWithoutFrontmatterAsPureBody() {
        SkillDocument document = SkillDocument.parse("# 没有 frontmatter\n内容");

        assertThat(document.frontmatter()).isEmpty();
        assertThat(document.body()).isEqualTo("# 没有 frontmatter\n内容");
    }

    /** frontmatter 开了头却没闭合：整篇当正文，不能把剩下的全部吞成元数据。 */
    @Test
    void treatsUnterminatedFrontmatterAsPureBody() {
        SkillDocument document = SkillDocument.parse("---\nname: broken\n还有正文");

        assertThat(document.frontmatter()).isEmpty();
        assertThat(document.body()).contains("name: broken");
    }

    @Test
    void toleratesNullAndBlankInput() {
        assertThat(SkillDocument.parse(null).frontmatter()).isEmpty();
        assertThat(SkillDocument.parse(null).body()).isEmpty();
        assertThat(SkillDocument.parse("   ").body()).isEmpty();
    }

    @Test
    void returnsNullForMissingKey() {
        assertThat(SkillDocument.parse("---\nname: x\n---\nbody").value("description")).isNull();
    }
}
