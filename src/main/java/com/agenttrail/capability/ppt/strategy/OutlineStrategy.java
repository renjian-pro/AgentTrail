package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationException;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptOutline;
import com.agenttrail.capability.ppt.PptOutlineSlide;
import com.agenttrail.capability.ppt.PptPrompts;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.loop.structured.JsonRepair;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * OUTLINE 状态（issue #24）：基于 REQUIREMENT + SEARCH 两个状态的产出，生成内容大纲
 * （{@link PptOutline}）——只规划"讲什么"，不知道最终填进哪个模板 shape，那是 SCHEMA 状态的事。
 */
public class OutlineStrategy implements PptGenerationStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * {@link #parseOutline} 解包 {@code {"content":"..."}} 包装的最大层数。超过这个深度
     * 仍未拿到 PptOutline 字段基本可以判定输入不是合法 JSON，转去宽容提取兜底。
     */
    private static final int MAX_UNWRAP_DEPTH = 3;

    private final AgentLoopExecutor executor;

    public OutlineStrategy(AgentLoopExecutor executor) {
        this.executor = executor;
    }

    @Override
    public PptState handledState() {
        return PptState.OUTLINE;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        RunnableParams params = new RunnableParams(context.conversationId(), "ppt-generation", java.util.Map.of(),
                OutputType.of(PptOutline.class));
        String prompt = PptPrompts.OUTLINE.formatted(renderInput(context));
        String rawJson = executor.call(prompt, params);
        try {
            PptOutline outline = parseOutline(rawJson);
            return context.withOutline(outline);
        } catch (Exception malformed) {
            throw new PptGenerationException(
                    "OUTLINE 状态解析失败，模型输出不是合法的 PptOutline JSON: " + rawJson, malformed);
        }
    }

    /**
     * 解析模型输出为 {@link PptOutline}。
     *
     * <p>正常情况：{@code rawJson} 已经是合法 JSON 树（{@link com.agenttrail.loop.structured.JsonRepair}
     * 在 {@code AgentLoopExecutor.call()} 里兜过一次），直接 readTree 后 treeToValue 即可。
     *
     * <p>异常情况：模型"结构化输出"有时会把 JSON 嵌在 {@code {"content":"..."}} 里——
     * 现有测试 {@code acceptsOutlineJsonWrappedInAContentString} 覆盖了这种格式，
     * 解包条件是"对象里只有 {@code content} 一个字段、且没有 {@code deckTitle}"。
     * 这里额外加了两道防线：
     * <ol>
     *   <li>解包深度限制在 {@value #MAX_UNWRAP_DEPTH} 层：{@code JsonRepair} 修复失败时
     *       会把损坏 JSON 包成 {@code {"content":"..."}}，如果无限解包就会被它困住；
     *   <li>解包时如果内层不是合法 JSON（readTree 抛异常），改走 {@link #extractOutlineLenient}
     *       从原始字符串中按字段名提取，而不是用又一个 content wrapper 树去 treeToValue
     *       然后等 Jackson 报 "Unrecognized field content"——后一种只是把错误推到上游，
     *       治不好"模型这次就是输出了损坏 JSON"这种问题。
     * </ol>
     */
    private static PptOutline parseOutline(String rawJson) throws java.io.IOException {
        JsonNode node = MAPPER.readTree(JsonRepair.fixJson(rawJson));

        int depth = 0;
        while (depth < MAX_UNWRAP_DEPTH
                && node.isObject()
                && node.has("content")
                && !node.has("deckTitle")
                && node.get("content").isTextual()) {
            String inner = node.get("content").asText();
            try {
                node = MAPPER.readTree(inner);
            } catch (java.io.IOException parseFailure) {
                // 内层是损坏 JSON：readTree 直接失败，再走一次 JsonRepair.fixJson 也只会
                // 再次被 wrapAsPlainContent 包成 content wrapper，等于零进展。直接用
                // 状态机按字段名从原始字符串中宽容提取 PptOutline
                return extractOutlineLenient(inner);
            }
            depth++;
        }

        return MAPPER.treeToValue(node, PptOutline.class);
    }

    /**
     * 从损坏的 JSON 字符串中按字段名宽容提取 {@link PptOutline}——状态机逐字符扫描，
     * 用与 {@link com.agenttrail.loop.structured.JsonRepair} 相同的"未转义引号按后续
     * 字符启发式判定是字符串结束还是字符串内容"规则，确保 {@code "戏剧原型中的"丑角"跳脱常规"}
     * 这类模型常见瑕疵也能解出正确边界。
     */
    private static PptOutline extractOutlineLenient(String brokenJson) {
        String deckTitle = extractSimpleStringField(brokenJson, "deckTitle");
        String deckSubtitle = extractSimpleStringField(brokenJson, "deckSubtitle");
        List<PptOutlineSlide> slides = extractSlidesLenient(brokenJson);
        return new PptOutline(deckTitle, deckSubtitle, slides);
    }

    private static String extractSimpleStringField(String json, String fieldName) {
        int keyStart = findKey(json, fieldName);
        if (keyStart < 0) {
            return null;
        }
        // 跳过 "fieldName" 整体（首尾各一个引号 + 字段名本体）
        int afterKey = keyStart + fieldName.length() + 2;
        int colon = indexOfStructural(json, afterKey, ':');
        if (colon < 0) {
            return null;
        }
        int quote = indexOfStructural(json, colon + 1, '"');
        if (quote < 0) {
            return null;
        }
        return readStringValueLenient(json, quote);
    }

    /** 找 {@code "fieldName"} 第一次出现的位置，且前面（跳过空白后）是 {@code {} / ,}。 */
    private static int findKey(String json, String fieldName) {
        String quoted = "\"" + fieldName + "\"";
        int idx = json.indexOf(quoted);
        if (idx < 0) {
            return -1;
        }
        int prev = idx - 1;
        while (prev >= 0 && Character.isWhitespace(json.charAt(prev))) {
            prev--;
        }
        if (prev < 0) {
            return idx;
        }
        char prevChar = json.charAt(prev);
        return (prevChar == '{' || prevChar == ',') ? idx : -1;
    }

    /** 从 {@code from} 起跳过空白与中间结构字符（{@code :} {@code ,}）后找第一个等于 {@code target} 的字符；找不到返回 -1。 */
    private static int indexOfStructural(String json, int from, char target) {
        int i = from;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == target) {
                return i;
            }
            if (!Character.isWhitespace(c) && c != ':' && c != ',') {
                // 遇到非空白、非分隔符的字符，结构预期不成立
                return -1;
            }
            i++;
        }
        return -1;
    }

    /**
     * 状态机读字符串值，启发式判定未转义引号是字符串结束还是字符串内字符。
     * 与 {@link com.agenttrail.loop.structured.JsonRepair} 启发式一致：跳过空白后下一字符是
     * {@code ,}/ {@code }}/ {@code ]}/ {@code :}/ EOF 才视为结束；否则视为内容，
     * 按出现顺序交替替换为左右中文引号（避免破坏 JSON 结构，也比裸引号更可读）。
     */
    private static String readStringValueLenient(String json, int firstQuote) {
        StringBuilder sb = new StringBuilder();
        int i = firstQuote + 1;
        boolean nextUnescapedIsOpen = false;  // 第一个内部引号是"开"位
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(c).append(json.charAt(i + 1));
                i += 2;
            } else if (c == '"') {
                int j = i + 1;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
                    j++;
                }
                if (j >= json.length()
                        || json.charAt(j) == ','
                        || json.charAt(j) == '}'
                        || json.charAt(j) == ']'
                        || json.charAt(j) == ':') {
                    return sb.toString();
                }
                sb.append(nextUnescapedIsOpen ? '“' : '”');
                nextUnescapedIsOpen = !nextUnescapedIsOpen;
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 提取 {@code slides} 数组：按栈式状态机定位顶层 {@code {…}}，每个 slide 对象单独解析。
     * 数组内部可能有未转义引号，所以大括号匹配和字符串边界判定都用与上面相同的启发式。
     */
    private static List<PptOutlineSlide> extractSlidesLenient(String json) {
        int slidesKey = findKey(json, "slides");
        if (slidesKey < 0) {
            return List.of();
        }
        int afterKey = slidesKey + "slides".length() + 2;
        int arrayStart = indexOfStructural(json, afterKey, '[');
        if (arrayStart < 0) {
            return List.of();
        }
        int arrayEnd = findMatchingBracketLenient(json, arrayStart, '[', ']');
        if (arrayEnd < 0) {
            return List.of();
        }
        String arrayBody = json.substring(arrayStart + 1, arrayEnd);

        List<PptOutlineSlide> slides = new java.util.ArrayList<>();
        int depth = 0;
        int objStart = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"' && looksLikeStringEndInArrayBody(arrayBody, i + 1)) {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    objStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String slideJson = arrayBody.substring(objStart, i + 1);
                    PptOutlineSlide slide = parseSlideLenient(slideJson);
                    if (slide != null) {
                        slides.add(slide);
                    }
                    objStart = -1;
                } else if (depth < 0) {
                    // slides 数组的 } 在外面才被吃掉，这里 depth<0 意味着已经读到数组外
                    break;
                }
            }
        }
        return slides;
    }

    private static boolean looksLikeStringEndInArrayBody(String arrayBody, int fromIdx) {
        int i = fromIdx;
        while (i < arrayBody.length() && Character.isWhitespace(arrayBody.charAt(i))) {
            i++;
        }
        if (i >= arrayBody.length()) {
            return true;
        }
        char next = arrayBody.charAt(i);
        return next == ',' || next == '}' || next == ']' || next == ':';
    }

    /** 找与 {@code json[start]} 匹配的右括号；用同样的字符串边界启发式跳过字符串内的方括号字符。 */
    private static int findMatchingBracketLenient(String json, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"' && looksLikeStringEndInArrayBody(json, i + 1)) {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static PptOutlineSlide parseSlideLenient(String slideJson) {
        String title = extractSimpleStringField(slideJson, "title");
        List<String> bullets = extractBulletsLenient(slideJson);
        return new PptOutlineSlide(title, bullets);
    }

    private static List<String> extractBulletsLenient(String slideJson) {
        int bulletsKey = findKey(slideJson, "bullets");
        if (bulletsKey < 0) {
            return List.of();
        }
        int afterKey = bulletsKey + "bullets".length() + 2;
        int arrayStart = indexOfStructural(slideJson, afterKey, '[');
        if (arrayStart < 0) {
            return List.of();
        }
        int arrayEnd = findMatchingBracketLenient(slideJson, arrayStart, '[', ']');
        if (arrayEnd < 0) {
            return List.of();
        }
        String arrayBody = slideJson.substring(arrayStart + 1, arrayEnd);

        List<String> bullets = new java.util.ArrayList<>();
        int i = 0;
        while (i < arrayBody.length()) {
            // 跳过空白与分隔符
            while (i < arrayBody.length()
                    && (Character.isWhitespace(arrayBody.charAt(i)) || arrayBody.charAt(i) == ',')) {
                i++;
            }
            if (i >= arrayBody.length() || arrayBody.charAt(i) != '"') {
                break;
            }
            String bullet = readStringValueLenient(arrayBody, i);
            if (bullet != null) {
                bullets.add(bullet);
            }
            // 跳过整个字符串值（含启发式处理过的内部引号）
            int j = i + 1;
            while (j < arrayBody.length()) {
                char c = arrayBody.charAt(j);
                if (c == '\\' && j + 1 < arrayBody.length()) {
                    j += 2;
                    continue;
                }
                if (c == '"' && looksLikeStringEndInArrayBody(arrayBody, j + 1)) {
                    i = j + 1;
                    break;
                }
                j++;
            }
            if (j >= arrayBody.length()) {
                break;
            }
        }
        return bullets;
    }

    private static String renderInput(PptGenerationContext context) {
        StringBuilder builder = new StringBuilder();
        var requirement = context.requirement();
        builder.append("【制作需求】\n")
                .append("标题：").append(requirement.title()).append('\n')
                .append("主题：").append(requirement.topic()).append('\n')
                .append("受众：").append(requirement.audience()).append('\n')
                .append("建议内容页数：").append(requirement.slideCount()).append('\n')
                .append("语言风格：").append(requirement.tone()).append("\n\n")
                .append("【检索素材】\n");
        var searchMaterials = context.searchMaterials() == null
                ? java.util.List.<String>of()
                : context.searchMaterials();
        for (String material : searchMaterials) {
            builder.append("- ").append(material).append('\n');
        }
        return builder.toString();
    }
}
