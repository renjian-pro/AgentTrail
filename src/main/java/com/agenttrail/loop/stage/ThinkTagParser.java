package com.agenttrail.loop.stage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把混排了 {@code <think>} 标签的流式文本，拆成"思考内容"和"正文"两类片段。
 *
 * <p>部分模型（MiniMax 等）不提供独立的思考字段，而是把推理过程和最终回答混在同一个
 * content 字段里，用标签分隔。前端要把两者分开渲染（思考折叠、正文常显），就得在这里拆。
 *
 * <p><b>有状态，一轮一个实例</b>：流式下标签会被切断在 chunk 边界上——上一块结尾是
 * {@code "<thi"}、下一块开头是 {@code "nk>"}。这半截既不能当正文吐出去（用户会看到乱码），
 * 也不能就地丢弃（可能它根本不是标签，而是正文里真的有个 {@code "<"}）。
 * 唯一正确的做法是**攒住等下一块**，所以解析器必须自己持有跨 chunk 的状态（踩坑点 #3）。
 *
 * <pre>{@code
 * ThinkTagParser parser = new ThinkTagParser();
 * for (String chunk : chunks) {
 *     parser.parse(chunk).forEach(this::emit);
 * }
 * parser.flush().forEach(this::emit);   // 收尾：把攒住没用上的内容吐出来
 * }</pre>
 */
public final class ThinkTagParser {

    /**
     * 只匹配到标签名为止、不含 {@code >}，这样 {@code <think>}、{@code <think/>}、
     * {@code <think type="...">} 这些变体都能命中；真正的标签结束位置再动态找 {@code >}。
     */
    private static final String THINK_OPEN = "<think";
    private static final String THINK_CLOSE = "</think";

    /** 匹配一对完整标签及其内容，用于非流式场景整体剥离。 */
    private static final String COMPLETE_THINK_BLOCK = "(?s)<think[^>]*>.*?</think[^>]*>";

    /** 最长的标签前缀（{@code </think}），决定了要为"可能是半截标签"攒住多少字符。 */
    private static final int LONGEST_TAG_PREFIX = THINK_CLOSE.length();

    /** 攒住的、可能是半截标签开头的尾部文本。 */
    private final StringBuilder pending = new StringBuilder();

    private boolean inThink;

    /**
     * 一段内容及其归属。
     *
     * @param thinking true 表示这段属于思考过程
     */
    public record Segment(boolean thinking, String content) {
    }

    /** 喂入一个流式 chunk，返回本次能确定归属的片段。 */
    public List<Segment> parse(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        pending.append(chunk);

        List<Segment> segments = new ArrayList<>();
        int cursor = 0;

        while (cursor < pending.length()) {
            int openAt = pending.indexOf(THINK_OPEN, cursor);
            int closeAt = pending.indexOf(THINK_CLOSE, cursor);

            if (openAt < 0 && closeAt < 0) {
                cursor = emitUpToPossibleTagStart(segments, cursor);
                break;
            }

            boolean opening = openAt >= 0 && (closeAt < 0 || openAt < closeAt);
            int tagAt = opening ? openAt : closeAt;
            addIfPresent(segments, inThink, pending.substring(cursor, tagAt));

            int tagEnd = pending.indexOf(">", tagAt);
            if (tagEnd < 0) {
                // 标签名认出来了但还没等到 '>'，整个标签攒住等下一块
                cursor = tagAt;
                break;
            }
            inThink = opening;
            cursor = tagEnd + 1;
        }

        pending.delete(0, cursor);
        return segments;
    }

    /**
     * 流结束时调用：把还攒着的内容按当前归属吐出去。
     *
     * <p>攒住的东西到这一刻已经确定不可能是标签了（后面不会再有内容），所以它就是普通文本。
     */
    public List<Segment> flush() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Segment> segments = List.of(new Segment(inThink, pending.toString()));
        pending.setLength(0);
        return segments;
    }

    /**
     * 没找到任何标签时，把尾部**可能是标签开头**的部分留下，其余的放行。
     *
     * <p>比如收到 {@code "正文<thi"}：{@code "<thi"} 是 {@code "<think"} 的前缀，
     * 有可能下一块补上 {@code "nk>"} 就成了标签，所以先攒着；{@code "正文"} 可以放心吐出去。
     *
     * @return 新的游标位置，即已确定放行的长度
     */
    private int emitUpToPossibleTagStart(List<Segment> segments, int cursor) {
        int safeEnd = pending.length();
        int lookbackFrom = Math.max(cursor, pending.length() - LONGEST_TAG_PREFIX);
        for (int i = lookbackFrom; i < pending.length(); i++) {
            if (pending.charAt(i) == '<' && couldStartATag(i)) {
                safeEnd = i;
                break;
            }
        }
        addIfPresent(segments, inThink, pending.substring(cursor, safeEnd));
        return safeEnd;
    }

    /** 从 {@code start} 到结尾的这段，是否是某个 think 标签的前缀。 */
    private boolean couldStartATag(int start) {
        String tail = pending.substring(start);
        return THINK_OPEN.startsWith(tail) || THINK_CLOSE.startsWith(tail);
    }

    /**
     * 从**完整**文本里整体剥掉 think 标签及其内容。
     *
     * <p>仅用于非流式场景（比如把历史消息喂给摘要模型之前）。流式场景必须用实例方法
     * {@link #parse}，因为这个正则要求标签成对完整，而流式下随时可能只到了一半。
     */
    public static String stripThinkTags(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.replaceAll(COMPLETE_THINK_BLOCK, "").trim();
    }

    private static void addIfPresent(List<Segment> segments, boolean thinking, String content) {
        if (!content.isEmpty()) {
            segments.add(new Segment(thinking, content));
        }
    }
}
