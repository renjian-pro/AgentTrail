package com.agenttrail.loop.stage;

import com.agenttrail.loop.stage.ThinkTagParser.Segment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 部分模型把思考过程和正文混在同一个字段里，用 {@code <think>} 标签分隔。
 *
 * <p>难点全在"流式"上：标签本身会被切断在两个 chunk 之间，比如上一块结尾是
 * {@code "<thi"}、下一块开头是 {@code "nk>"}。解析器必须能跨 chunk 续接（踩坑点 #3）。
 */
class ThinkTagParserTest {

    private final ThinkTagParser parser = new ThinkTagParser();

    @Test
    void treatsPlainTextAsNormalOutput() {
        assertThat(parseAll("你好，世界")).containsExactly(new Segment(false, "你好，世界"));
    }

    @Test
    void splitsThinkingFromNormalTextWithinOneChunk() {
        assertThat(parseAll("正文A<think>推理过程</think>正文B")).containsExactly(
                new Segment(false, "正文A"),
                new Segment(true, "推理过程"),
                new Segment(false, "正文B"));
    }

    /** 上一块停在 think 标签内部，下一块的开头依然属于思考内容。 */
    @Test
    void carriesTheThinkingStateAcrossChunks() {
        assertThat(parseAll("正文<think>推理开头", "推理结尾</think>正文结尾")).containsExactly(
                new Segment(false, "正文"),
                new Segment(true, "推理开头"),
                new Segment(true, "推理结尾"),
                new Segment(false, "正文结尾"));
    }

    /** 标签被切成两半时，半截标签既不能当正文吐出去，也不能丢——要攒住等下一块。 */
    @Test
    void doesNotLeakAHalfArrivedTagAsVisibleText() {
        List<Segment> segments = parseAll("正文<thi", "nk>推理</think>结尾");

        assertThat(visibleTextOf(segments)).isEqualTo("正文结尾");
        assertThat(thinkingTextOf(segments)).isEqualTo("推理");
    }

    /** 标签名认出来了但 {@code >} 还没到，同样要攒住。 */
    @Test
    void waitsForTheClosingAngleBracketBeforeSwitchingMode() {
        List<Segment> segments = parseAll("正文<think", ">推理</think>结尾");

        assertThat(visibleTextOf(segments)).isEqualTo("正文结尾");
        assertThat(thinkingTextOf(segments)).isEqualTo("推理");
    }

    /** 正文里本来就有个 {@code <}、后面并不是标签——攒住之后要能正常放行，不能吞掉。 */
    @Test
    void releasesHeldBackTextThatTurnsOutNotToBeATag() {
        List<Segment> segments = parseAll("比较 a <", " b 的大小");

        assertThat(visibleTextOf(segments)).isEqualTo("比较 a < b 的大小");
    }

    /** 流结束时仍攒着的内容必须吐出来，否则用户会丢掉最后几个字。 */
    @Test
    void flushesTextStillHeldBackWhenTheStreamEnds() {
        parser.parse("正文<");

        assertThat(parser.flush()).containsExactly(new Segment(false, "<"));
    }

    @Test
    void handlesTagsWithAttributes() {
        assertThat(parseAll("<think type=\"reasoning\">推理</think>正文")).containsExactly(
                new Segment(true, "推理"),
                new Segment(false, "正文"));
    }

    @Test
    void returnsNothingForEmptyInput() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void stripsThinkTagsAndTheirContentFromCompleteText() {
        assertThat(ThinkTagParser.stripThinkTags("正文A<think>推理</think>正文B")).isEqualTo("正文A正文B");
    }

    @Test
    void leavesTextWithoutThinkTagsAlone() {
        assertThat(ThinkTagParser.stripThinkTags("纯正文")).isEqualTo("纯正文");
        assertThat(ThinkTagParser.stripThinkTags(null)).isNull();
    }

    /** 逐块喂进去并收尾，模拟一次完整的流式到达。 */
    private List<Segment> parseAll(String... chunks) {
        List<Segment> all = new ArrayList<>();
        for (String chunk : chunks) {
            all.addAll(parser.parse(chunk));
        }
        all.addAll(parser.flush());
        return all;
    }

    private static String visibleTextOf(List<Segment> segments) {
        return concat(segments, false);
    }

    private static String thinkingTextOf(List<Segment> segments) {
        return concat(segments, true);
    }

    private static String concat(List<Segment> segments, boolean thinking) {
        return segments.stream()
                .filter(segment -> segment.thinking() == thinking)
                .map(Segment::content)
                .reduce("", String::concat);
    }
}
