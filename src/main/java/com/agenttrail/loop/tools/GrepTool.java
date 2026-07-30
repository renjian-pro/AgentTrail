package com.agenttrail.loop.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * grep 工具：优先调系统 ripgrep，不可用时退化到纯 Java 实现。
 *
 * <p><b>为什么要两套实现</b>：ripgrep 在大仓库上比 JVM 里逐文件读快一个数量级（并行遍历、
 * 自动跳过 .gitignore 和二进制文件），能用当然用它；但它是一个**外部二进制依赖**，
 * 容器镜像里没装、PATH 没配、Windows 上没有，都很常见。工具因为"环境里少个二进制"
 * 而彻底不可用，对 Agent 来说是能力缺失，不是环境问题——所以必须有纯 Java 兜底。
 *
 * <p><b>退化发生在两个时机</b>，缺一不可：
 * <ol>
 *   <li>构造时探测一次（{@code rg --version}），避免每次调用都白起一个进程；
 *   <li>真正调用时如果进程起不来（探测之后 PATH 变了、二进制被裁掉了），当场落回 Java 实现
 *       并把开关永久关掉——参考实现只有第一种，第二种情况会直接把 IOException 甩给模型。
 * </ol>
 */
public final class GrepTool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);

    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final long RIPGREP_TIMEOUT_SECONDS = 60;
    /** 判定二进制文件时只看开头这些字节：文本文件的 NUL 字节几乎必然出现在很靠前的位置。 */
    private static final int BINARY_SNIFF_BYTES = 8_000;

    private static final String MODE_CONTENT = "content";
    private static final String MODE_FILES = "files_with_matches";
    private static final String MODE_COUNT = "count";

    private final PathSandbox sandbox;
    private final Charset charset;
    private final String ripgrepExecutable;
    private final ToolCallback toolCallback;

    /** 运行期可能被关掉（退化），所以是 volatile 而不是 final。 */
    private volatile boolean useRipgrep;

    private GrepTool(Builder builder) {
        this.sandbox = builder.sandbox != null ? builder.sandbox : PathSandbox.unrestricted();
        this.charset = builder.charset != null ? builder.charset : StandardCharsets.UTF_8;
        this.ripgrepExecutable = builder.ripgrepExecutable;
        BooleanSupplier availability = builder.ripgrepAvailability != null
                ? builder.ripgrepAvailability
                : this::probeRipgrep;
        this.useRipgrep = availability.getAsBoolean();
        this.toolCallback = buildCallback();
        log.debug("GrepTool 初始化完成，ripgrep={}", useRipgrep ? "可用" : "不可用（走纯 Java 实现）");
    }

    public static Builder builder() {
        return new Builder();
    }

    public ToolCallback toolCallback() {
        return toolCallback;
    }

    /** 当前是否还在走 ripgrep。退化之后会变成 false。 */
    public boolean usingRipgrep() {
        return useRipgrep;
    }

    /**
     * 内容搜索。
     *
     * @param outputMode content（默认，输出匹配行）/ files_with_matches（只输出文件路径）/ count（每个文件的命中数）
     */
    public String grep(String pattern, String path, String glob, String outputMode,
                       Integer beforeContext, Integer afterContext, Boolean ignoreCase,
                       Integer headLimit, Integer offset) {
        if (pattern == null || pattern.isBlank()) {
            return "Error: pattern 不能为空";
        }

        try {
            Path searchPath = sandbox.resolve(path);
            if (!Files.exists(searchPath)) {
                return "Error: 路径不存在：" + searchPath;
            }

            String mode = normaliseMode(outputMode);
            int before = beforeContext != null ? Math.max(0, beforeContext) : 0;
            int after = afterContext != null ? Math.max(0, afterContext) : 0;
            boolean caseInsensitive = Boolean.TRUE.equals(ignoreCase);
            int limit = headLimit != null && headLimit > 0 ? headLimit : DEFAULT_HEAD_LIMIT;
            int skip = offset != null && offset > 0 ? offset : 0;

            // 先编译一次正则：非法正则要立刻变成一条模型能读懂的错误，
            // 而不是等 ripgrep 用它自己的语法报一段风格完全不同的错
            Pattern regex = Pattern.compile(pattern, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);

            List<String> lines = null;
            if (useRipgrep) {
                try {
                    lines = searchWithRipgrep(pattern, searchPath, glob, mode, before, after, caseInsensitive);
                } catch (IOException ripgrepUnavailable) {
                    // 探测时还在，真调用时没了：当场退化，并且不再重试——
                    // 每次调用都白起一个必然失败的进程是纯粹的浪费
                    log.warn("ripgrep 调用失败，永久退化到纯 Java 实现：{}", ripgrepUnavailable.getMessage());
                    useRipgrep = false;
                }
            }
            if (lines == null) {
                lines = searchWithJava(regex, searchPath, glob, mode, before, after);
            }

            return format(paginate(lines, skip, limit));
        } catch (SandboxViolationException violation) {
            log.warn("grep 触发目录白名单拦截：{}", violation.getMessage());
            return "Error: " + violation.getMessage();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "Error: 搜索被中断";
        } catch (IOException | RuntimeException failure) {
            log.error("grep 执行失败：{}", failure.getMessage(), failure);
            return "Error: " + failure.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // ripgrep 实现
    // ------------------------------------------------------------------

    private boolean probeRipgrep() {
        try {
            Process process = new ProcessBuilder(ripgrepExecutable, "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // 探测也要有超时：一个卡住的探测会把整个工具的构造过程挂死
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException notInstalled) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<String> searchWithRipgrep(String pattern, Path searchPath, String glob, String mode,
                                           int before, int after, boolean ignoreCase)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ripgrepExecutable);
        command.add("--line-number");
        if (ignoreCase) {
            command.add("--ignore-case");
        }
        // 前后上下文分开传：参考实现用 -C max(before, after)，一边要 0 行一边要 3 行时会多给
        if (before > 0) {
            command.add("--before-context");
            command.add(String.valueOf(before));
        }
        if (after > 0) {
            command.add("--after-context");
            command.add(String.valueOf(after));
        }
        if (glob != null && !glob.isBlank()) {
            command.add("--glob");
            command.add(glob);
        }
        switch (mode) {
            case MODE_FILES -> command.add("--files-with-matches");
            case MODE_COUNT -> command.add("--count");
            default -> { /* content 模式不需要额外开关 */ }
        }
        command.add("--");
        command.add(pattern);
        command.add(searchPath.toString());

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        List<String> output = readLines(process.getInputStream());
        if (!process.waitFor(RIPGREP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("ripgrep 执行超时");
        }
        // rg 的约定：0 = 有匹配，1 = 没匹配，>=2 才是真出错
        if (process.exitValue() >= 2) {
            throw new IOException("ripgrep 返回错误码 " + process.exitValue()
                    + (output.isEmpty() ? "" : "：" + output.get(0)));
        }
        return output;
    }

    private List<String> readLines(InputStream stream) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // 纯 Java 实现
    // ------------------------------------------------------------------

    private List<String> searchWithJava(Pattern regex, Path searchPath, String glob, String mode,
                                        int before, int after) throws IOException {
        List<String> results = new ArrayList<>();
        for (Path file : collectFiles(searchPath, glob)) {
            searchOneFile(file, regex, mode, before, after, results);
        }
        return results;
    }

    private List<Path> collectFiles(Path searchPath, String glob) throws IOException {
        PathMatcher matcher = (glob == null || glob.isBlank() || "*".equals(glob))
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob.replace('\\', '/'));

        if (Files.isRegularFile(searchPath)) {
            return matches(matcher, searchPath, searchPath.getParent())
                    ? List.of(searchPath) : List.of();
        }
        try (Stream<Path> walk = Files.walk(searchPath)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> matches(matcher, file, searchPath))
                    .sorted()
                    .toList();
        }
    }

    private static boolean matches(PathMatcher matcher, Path file, Path root) {
        if (matcher == null) {
            return true;
        }
        // 文件名和相对路径都试：'*.java' 按文件名匹配，'src/**.java' 按相对路径匹配
        if (matcher.matches(file.getFileName())) {
            return true;
        }
        return root != null && file.startsWith(root) && matcher.matches(root.relativize(file));
    }

    private void searchOneFile(Path file, Pattern regex, String mode, int before, int after,
                               List<String> results) {
        try {
            if (isBinary(file)) {
                // 二进制文件按文本搜出来的"匹配行"是乱码，一个 jar 就能塞满上下文
                return;
            }
            List<String> lines = EncodingFallbackReader.readLines(file);
            List<Integer> matchedLines = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                if (regex.matcher(lines.get(index)).find()) {
                    matchedLines.add(index);
                }
            }
            if (matchedLines.isEmpty()) {
                return;
            }

            switch (mode) {
                case MODE_FILES -> results.add(file.toString());
                case MODE_COUNT -> results.add(file + ":" + matchedLines.size());
                default -> renderContent(file, lines, matchedLines, before, after, results);
            }
        } catch (IOException unreadable) {
            log.debug("跳过读不了的文件：{}（{}）", file, unreadable.getMessage());
        }
    }

    /**
     * 输出匹配行及其上下文。
     *
     * <p>上下文窗口要**先合并再输出**：两个相邻匹配各自的窗口重叠时，逐个匹配输出会把
     * 同一行打印两遍（参考实现就是这样），既浪费上下文又让模型误判命中次数。
     */
    private void renderContent(Path file, List<String> lines, List<Integer> matchedLines,
                               int before, int after, List<String> results) {
        Set<Integer> matched = new LinkedHashSet<>(matchedLines);
        int cursor = -1;
        for (int matchIndex : matchedLines) {
            int from = Math.max(0, matchIndex - before);
            int to = Math.min(lines.size() - 1, matchIndex + after);
            // cursor 记录已经输出到哪一行，天然把重叠的窗口合并掉
            from = Math.max(from, cursor + 1);
            for (int index = from; index <= to; index++) {
                // 和 grep 一致：命中行用 ':' 分隔，上下文行用 '-' 分隔，模型一眼能分清
                char separator = matched.contains(index) ? ':' : '-';
                results.add(file + String.valueOf(separator) + (index + 1) + separator + lines.get(index));
            }
            cursor = Math.max(cursor, to);
        }
    }

    private static boolean isBinary(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] head = stream.readNBytes(BINARY_SNIFF_BYTES);
            for (byte value : head) {
                if (value == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 公共部分
    // ------------------------------------------------------------------

    private static String normaliseMode(String outputMode) {
        if (outputMode == null || outputMode.isBlank()) {
            return MODE_CONTENT;
        }
        return switch (outputMode.trim()) {
            case MODE_FILES, MODE_COUNT -> outputMode.trim();
            default -> MODE_CONTENT;
        };
    }

    private static List<String> paginate(List<String> lines, int offset, int headLimit) {
        if (offset >= lines.size()) {
            return List.of();
        }
        List<String> window = lines.subList(offset, lines.size());
        return window.size() > headLimit ? window.subList(0, headLimit) : window;
    }

    private static String format(List<String> lines) {
        return lines.isEmpty() ? "No matches found." : String.join("\n", lines);
    }

    private ToolCallback buildCallback() {
        return new JsonToolCallback("grep", """
                基于正则表达式的文件内容搜索。

                用法:
                - 搜内容一律用本工具，不要通过 bash 执行 grep/findstr
                - 支持完整的 Java 正则语法（如 "log.*Error"、"class\\\\s+\\\\w+"）
                - glob 按文件类型过滤（如 '*.java'）
                - output_mode: 'content' 输出匹配行（默认）、'files_with_matches' 只输出文件路径、
                  'count' 输出每个文件的命中数。先用 files_with_matches 缩小范围，再用 content 看细节
                - head_limit / offset 分页，默认最多返回 %d 条
                """.formatted(DEFAULT_HEAD_LIMIT), """
                {"type":"object","properties":{\
                "pattern":{"type":"string","description":"【必填】正则表达式"},\
                "path":{"type":"string","description":"搜索的文件或目录，默认当前工作目录"},\
                "glob":{"type":"string","description":"文件名过滤模式，如 '*.java'"},\
                "output_mode":{"type":"string","enum":["content","files_with_matches","count"],\
                "description":"输出形式，默认 content"},\
                "before_context":{"type":"integer","description":"匹配行之前显示几行上下文"},\
                "after_context":{"type":"integer","description":"匹配行之后显示几行上下文"},\
                "ignore_case":{"type":"boolean","description":"是否忽略大小写，默认 false"},\
                "head_limit":{"type":"integer","description":"最多返回多少条结果"},\
                "offset":{"type":"integer","description":"跳过前多少条结果"}},\
                "required":["pattern"]}""",
                args -> grep(args.text("pattern"), args.text("path"), args.text("glob"),
                        args.text("output_mode"), args.integer("before_context"),
                        args.integer("after_context"), args.flag("ignore_case"),
                        args.integer("head_limit"), args.integer("offset")));
    }

    /** 构建器。 */
    public static final class Builder {

        private PathSandbox sandbox;
        private Charset charset;
        private String ripgrepExecutable = "rg";
        private BooleanSupplier ripgrepAvailability;

        public Builder sandbox(PathSandbox sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        public Builder allowedDirs(String... allowedDirs) {
            this.sandbox = PathSandbox.restrictedTo(allowedDirs);
            return this;
        }

        public Builder charset(Charset charset) {
            this.charset = charset;
            return this;
        }

        public Builder ripgrepExecutable(String ripgrepExecutable) {
            this.ripgrepExecutable = ripgrepExecutable;
            return this;
        }

        /**
         * 覆盖 ripgrep 可用性的判定方式。
         *
         * <p>做成可注入的，是因为"ripgrep 不可用时退化"这条路径否则只能在没装 ripgrep 的机器上
         * 测到——换台装了 ripgrep 的 CI，这条分支就再也跑不到了，等于没测。
         */
        public Builder ripgrepAvailability(BooleanSupplier ripgrepAvailability) {
            this.ripgrepAvailability = ripgrepAvailability;
            return this;
        }

        public GrepTool build() {
            return new GrepTool(this);
        }
    }
}
