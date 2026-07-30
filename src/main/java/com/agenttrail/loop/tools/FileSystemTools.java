package com.agenttrail.loop.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件系统工具：read_file / write_file / edit_file / list_files / glob_files。
 *
 * <p>全部操作都经过 {@link PathSandbox} 的目录白名单校验（#41）。三个关键设计点：
 * <ul>
 *   <li><b>#17 文档与实现必须一致</b>：参考实现的工具描述写"只能创建新文件，已存在会报错"，
 *       实现却是 {@code CREATE + TRUNCATE_EXISTING}——永远静默覆盖。这里选择让实现服从描述：
 *       默认拒绝覆盖，要覆盖必须显式传 {@code overwrite=true}。理由是模型看到的只有描述，
 *       描述说"会报错"它就敢在不确定的时候试一把，静默覆盖的代价是用户文件没了。
 *   <li><b>#18 编辑的唯一性校验</b>：待替换文本命中多处时直接拒绝，而不是替换第一个或全部——
 *       两种默认行为都可能是"静默的错误批量操作"。
 *   <li><b>#19 三级编码兜底</b>：读和编辑都走 {@link EncodingFallbackReader}，
 *       并且编辑按原编码写回，不静默转码。
 * </ul>
 *
 * <p>为什么手写 JSON Schema 而不是用注解自动生成：schema 是给模型看的"接口文档"，
 * 参数描述的措辞直接影响模型传参的正确率，值得手工控制；顺带也避免了运行期反射扫描。
 */
public final class FileSystemTools {

    private static final Logger log = LoggerFactory.getLogger(FileSystemTools.class);

    private static final int DEFAULT_MAX_FILE_SIZE_MB = 10;
    private static final int DEFAULT_LINE_LIMIT = 500;
    /** 单行超长时切块输出，避免一行几十万字符直接把上下文顶爆。 */
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int LINE_NUMBER_WIDTH = 6;
    /** glob 结果条数上限：一次匹配上万个文件对模型没有意义，只会挤占上下文。 */
    private static final int MAX_GLOB_RESULTS = 500;
    private static final String EMPTY_FILE_NOTICE = "（文件存在，但内容为空）";

    private final PathSandbox sandbox;
    private final long maxFileSizeBytes;
    private final int defaultLineLimit;
    private final List<ToolCallback> toolCallbacks;

    private FileSystemTools(Builder builder) {
        this.sandbox = builder.sandbox != null ? builder.sandbox : PathSandbox.unrestricted();
        this.maxFileSizeBytes = (long) builder.maxFileSizeMb * 1024L * 1024L;
        this.defaultLineLimit = builder.defaultLineLimit;
        this.toolCallbacks = List.of(readFileCallback(), writeFileCallback(), editFileCallback(),
                listFilesCallback(), globFilesCallback());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 五个工具各自是一个独立的 ToolCallback——loop 侧按工具名建索引，一个名字对一个实现。 */
    public List<ToolCallback> toolCallbacks() {
        return toolCallbacks;
    }

    // ------------------------------------------------------------------
    // read_file
    // ------------------------------------------------------------------

    /**
     * 读文件，返回 {@code cat -n} 形状的带行号内容。
     *
     * <p>带行号是为了让后续的 edit_file 有稳定的定位参照，也让模型在描述改动时能引用行号。
     */
    public String readFile(String path, Integer offset, Integer limit) {
        return guarded("read_file", () -> {
            Path file = sandbox.resolve(path);
            if (!Files.exists(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return "Error: 文件不存在：" + file;
            }
            long size = Files.size(file);
            if (size > maxFileSizeBytes) {
                return "Error: 文件大小 " + (size / 1024 / 1024) + "MB 超过上限 "
                        + (maxFileSizeBytes / 1024 / 1024) + "MB，请改用 grep 定位后再按行读取";
            }

            String content = EncodingFallbackReader.read(file).text();
            if (content.isEmpty()) {
                return EMPTY_FILE_NOTICE;
            }

            String[] lines = splitLines(content);
            int start = offset != null ? offset : 0;
            if (start < 0) {
                return "Error: offset 不能为负数";
            }
            if (start >= lines.length) {
                return "Error: offset " + start + " 超出文件行数（共 " + lines.length + " 行）";
            }
            int end = Math.min(start + (limit != null ? limit : defaultLineLimit), lines.length);

            StringBuilder rendered = new StringBuilder();
            for (int index = start; index < end; index++) {
                appendNumberedLine(rendered, index + 1, lines[index]);
            }
            if (end < lines.length) {
                rendered.append("... （还有 ").append(lines.length - end)
                        .append(" 行未显示，继续读请传 offset=").append(end).append('）');
            }
            return rendered.toString().stripTrailing();
        });
    }

    // ------------------------------------------------------------------
    // write_file
    // ------------------------------------------------------------------

    /**
     * 写文件。默认**拒绝覆盖已存在的文件**（#17），必须显式 {@code overwrite=true} 才覆盖。
     */
    public String writeFile(String path, String content, boolean overwrite) {
        return guarded("write_file", () -> {
            if (path == null || path.isBlank()) {
                return "Error: path 不能为空，必须给出含文件名的路径";
            }
            Path file = sandbox.resolve(path);
            if (Files.isDirectory(file)) {
                return "Error: 目标是一个目录，不能当文件写：" + file;
            }
            if (Files.exists(file) && !overwrite) {
                return "Error: 文件已存在：" + file
                        + "。修改已有文件请用 edit_file；确实要整体覆盖请显式传 overwrite=true";
            }

            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return "已写入文件：" + file + "（" + bytes.length + " 字节）";
        });
    }

    // ------------------------------------------------------------------
    // edit_file
    // ------------------------------------------------------------------

    /**
     * 按字符串替换编辑文件。
     *
     * <p>踩坑点 #18：{@code oldString} 命中多处且没显式要求 {@code replaceAll} 时直接报错。
     * 这逼调用方要么补上下文让匹配唯一，要么显式声明"我就是要全改"——把一个可能改错地方的
     * 静默操作，变成一次必须表态的选择。
     */
    public String editFile(String path, String oldString, String newString, boolean replaceAll) {
        return guarded("edit_file", () -> {
            Path file = sandbox.resolve(path);
            if (!Files.exists(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return "Error: 文件不存在：" + file;
            }
            if (oldString == null || oldString.isEmpty()) {
                return "Error: old_string 不能为空";
            }
            if (oldString.equals(newString)) {
                return "Error: new_string 必须和 old_string 不同，否则这次编辑没有意义";
            }

            // 读和写用同一种编码：参考实现读用 UTF-8 硬编码，GBK 文件读得出来却编辑不了，
            // 就算编辑成功也会被静默改写成 UTF-8（#19）
            EncodingFallbackReader.Decoded decoded = EncodingFallbackReader.read(file);
            String content = decoded.text();
            int occurrences = countOccurrences(content, oldString);

            if (occurrences == 0) {
                return "Error: 文件里找不到这段文本（注意缩进和换行必须完全一致）：" + preview(oldString);
            }
            if (occurrences > 1 && !replaceAll) {
                return "Error: 这段文本在文件里出现了 " + occurrences + " 次，无法确定要改哪一处。"
                        + "请补充上下文让 old_string 唯一，或显式传 replace_all=true 全部替换";
            }

            String replacement = newString != null ? newString : "";
            String updated = replaceAll
                    ? content.replace(oldString, replacement)
                    : replaceFirstLiteral(content, oldString, replacement);
            Files.write(file, updated.getBytes(decoded.charset()),
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            int replaced = replaceAll ? occurrences : 1;
            return "已编辑文件：" + file + "（替换 " + replaced + " 处，编码 " + decoded.charset().name() + "）";
        });
    }

    // ------------------------------------------------------------------
    // list_files
    // ------------------------------------------------------------------

    /** 列目录（不递归）。目录项带 {@code /} 后缀，让模型一眼分清文件和目录。 */
    public String listFiles(String path) {
        return guarded("list_files", () -> {
            Path dir = sandbox.resolve(path);
            if (!Files.isDirectory(dir)) {
                return "Error: 目录不存在：" + dir;
            }

            List<String> entries = new ArrayList<>();
            try (Stream<Path> children = Files.list(dir)) {
                List<Path> sorted = children.sorted(Comparator.comparing(child -> child.getFileName().toString()))
                        .toList();
                for (Path child : sorted) {
                    entries.add(describeEntry(child));
                }
            }
            if (entries.isEmpty()) {
                return dir + "：（空目录）";
            }
            // 只输出条目名而不是每行一个绝对路径：同一个目录下的绝对路径前缀完全重复，
            // 纯属浪费上下文，目录本身在首行给出一次就够了
            return dir + "：\n" + String.join("\n", entries);
        });
    }

    // ------------------------------------------------------------------
    // glob_files
    // ------------------------------------------------------------------

    /** 按 glob 模式找文件。相对模式基于沙箱基准目录，绝对模式的搜索根同样要过白名单校验。 */
    public String globFiles(String pattern) {
        return guarded("glob_files", () -> {
            if (pattern == null || pattern.isBlank()) {
                return "Error: pattern 不能为空";
            }

            Path searchRoot = sandbox.baseDir();
            String globPattern = pattern.trim();
            if (isAbsolutePattern(globPattern)) {
                int globStart = findGlobStart(globPattern);
                if (globStart > 0) {
                    searchRoot = Paths.get(globPattern.substring(0, globStart)).normalize();
                    globPattern = globPattern.substring(globStart);
                } else {
                    searchRoot = Paths.get(globPattern).normalize();
                    globPattern = "*";
                }
                // 绝对模式的搜索根必须落在白名单内，否则 glob 就成了绕过沙箱的后门
                sandbox.requireInside(searchRoot);
            }
            if (!Files.isDirectory(searchRoot)) {
                return "Error: 搜索根目录不存在：" + searchRoot;
            }

            PathMatcher matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + globPattern.replace('\\', '/'));
            Path root = searchRoot;
            List<String> matched = new ArrayList<>();
            // 只有模式里出现 ** 才递归；否则一个 *.java 也要走遍整棵目录树，代价白扔
            int depth = globPattern.contains("**") ? Integer.MAX_VALUE : 1;
            try (Stream<Path> candidates = Files.walk(root, depth)) {
                candidates.filter(Files::isRegularFile)
                        .filter(file -> matches(matcher, root, file))
                        .sorted()
                        .limit(MAX_GLOB_RESULTS + 1L)
                        .forEach(file -> matched.add(file.toString()));
            }

            if (matched.isEmpty()) {
                return "没有文件匹配模式：" + pattern;
            }
            if (matched.size() > MAX_GLOB_RESULTS) {
                return String.join("\n", matched.subList(0, MAX_GLOB_RESULTS))
                        + "\n... （结果超过 " + MAX_GLOB_RESULTS + " 条已截断，请用更精确的模式）";
            }
            return String.join("\n", matched);
        });
    }

    // ------------------------------------------------------------------
    // ToolCallback 定义
    // ------------------------------------------------------------------

    private ToolCallback readFileCallback() {
        return new JsonToolCallback("read_file", """
                读取文件内容，返回带行号的文本（形如 cat -n）。

                用法:
                - path 支持绝对路径和相对路径，相对路径基于当前工作目录解析
                - 默认从头读最多 %d 行；大文件先用 offset=0, limit=100 看结构，再按需翻页
                - 编辑文件之前必须先用本工具读一遍，确认待替换文本的确切写法（含缩进）
                """.formatted(DEFAULT_LINE_LIMIT), """
                {"type":"object","properties":{\
                "path":{"type":"string","description":"【必填】要读取的文件路径，绝对或相对路径"},\
                "offset":{"type":"integer","description":"起始行偏移量，从 0 开始，默认 0"},\
                "limit":{"type":"integer","description":"最多读取多少行，默认 %d"}},\
                "required":["path"]}""".formatted(DEFAULT_LINE_LIMIT),
                args -> readFile(args.text("path"), args.integer("offset"), args.integer("limit")));
    }

    private ToolCallback writeFileCallback() {
        return new JsonToolCallback("write_file", """
                创建文件并写入内容。

                用法:
                - path 必须含文件名；父目录不存在会自动创建
                - **文件已存在时默认报错**，不会静默覆盖；修改已有文件请用 edit_file
                - 确实需要整体覆盖已有文件时，显式传 overwrite=true
                - 内容以 UTF-8 写入
                """, """
                {"type":"object","properties":{\
                "path":{"type":"string","description":"【必填】要写入的文件路径，必须包含文件名"},\
                "content":{"type":"string","description":"【必填】要写入的完整文件内容"},\
                "overwrite":{"type":"boolean","description":"文件已存在时是否覆盖，默认 false（报错）"}},\
                "required":["path","content"]}""",
                args -> writeFile(args.text("path"), args.text("content"), args.flag("overwrite")));
    }

    private ToolCallback editFileCallback() {
        return new JsonToolCallback("edit_file", """
                通过字符串替换编辑已有文件。

                用法:
                - 编辑前先用 read_file 读一遍，old_string 必须和文件内容完全一致（含缩进、换行）
                - **old_string 在文件里必须唯一**：命中多处时本工具会拒绝执行，
                  请补充上下文让它唯一，或显式传 replace_all=true 全部替换
                - new_string 必须和 old_string 不同
                - 文件原有编码会被保留（不会静默转成 UTF-8）
                """, """
                {"type":"object","properties":{\
                "path":{"type":"string","description":"【必填】要编辑的文件路径"},\
                "old_string":{"type":"string","description":"【必填】被替换的原文，必须与文件内容逐字符一致"},\
                "new_string":{"type":"string","description":"【必填】替换后的新文本，必须与 old_string 不同"},\
                "replace_all":{"type":"boolean","description":"是否替换全部匹配，默认 false（命中多处则报错）"}},\
                "required":["path","old_string","new_string"]}""",
                args -> editFile(args.text("path"), args.text("old_string"),
                        args.text("new_string"), args.flag("replace_all")));
    }

    private ToolCallback listFilesCallback() {
        return new JsonToolCallback("list_files", """
                列出目录下的文件和子目录（不递归）。目录项以 / 结尾。

                用法:
                - path 不传则列出当前工作目录
                - 读文件或编辑文件之前，先用本工具确认路径确实存在
                """, """
                {"type":"object","properties":{\
                "path":{"type":"string","description":"要列出的目录路径，默认当前工作目录"}},\
                "required":[]}""",
                args -> listFiles(args.text("path")));
    }

    private ToolCallback globFilesCallback() {
        return new JsonToolCallback("glob_files", """
                按 glob 模式查找文件，返回匹配到的路径列表。

                用法:
                - 支持 *（任意字符）、**（跨目录层级）、?（单个字符）
                - 模式里出现 ** 才会递归子目录
                - 例：'**/*.java' 递归找所有 Java 文件，'*.txt' 只找当前目录
                - 找文件用本工具，找内容用 grep
                """, """
                {"type":"object","properties":{\
                "pattern":{"type":"string","description":"【必填】glob 匹配模式，例如 '**/*.java'"}},\
                "required":["pattern"]}""",
                args -> globFiles(args.text("pattern")));
    }

    // ------------------------------------------------------------------
    // 内部工具方法
    // ------------------------------------------------------------------

    /**
     * 统一的异常收口：所有对外方法都返回字符串，从不抛异常。
     *
     * <p>和 {@code ToolCallExecutor} 的约定一致——工具层的失败是"一条模型能读懂的结果"，
     * 不是"一次需要中断循环的故障"。越权访问单独记 warn，因为那是安全事件而不是手滑。
     */
    private String guarded(String toolName, IoSupplier<String> action) {
        try {
            return action.get();
        } catch (SandboxViolationException violation) {
            log.warn("{} 触发目录白名单拦截：{}", toolName, violation.getMessage());
            return "Error: " + violation.getMessage();
        } catch (IOException ioFailure) {
            log.error("{} 发生 IO 错误：{}", toolName, ioFailure.getMessage(), ioFailure);
            return "Error: " + ioFailure.getMessage();
        } catch (RuntimeException failure) {
            log.error("{} 执行失败：{}", toolName, failure.getMessage(), failure);
            return "Error: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    /** 允许抛 IOException 的 {@code Supplier}。 */
    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    /** 按 \n 切分并去掉行尾的 \r，这样 CRLF 文件的行号和内容都不会带上多余字符。 */
    private static String[] splitLines(String content) {
        String[] lines = content.split("\n", -1);
        int length = lines.length;
        if (length > 0 && lines[length - 1].isEmpty()) {
            length--;
        }
        String[] cleaned = new String[length];
        for (int index = 0; index < length; index++) {
            String line = lines[index];
            cleaned[index] = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        }
        return cleaned;
    }

    /** 超长行切块输出，续行用 {@code 行号.块号} 标记，保证行号列始终对齐可读。 */
    private static void appendNumberedLine(StringBuilder target, int lineNumber, String line) {
        // 显式用 \n 而不是 %n：输出是喂给模型的文本，不该随运行平台的换行符变化
        if (line.length() <= MAX_LINE_LENGTH) {
            target.append(String.format("%" + LINE_NUMBER_WIDTH + "d\t%s\n", lineNumber, line));
            return;
        }
        int chunks = (line.length() + MAX_LINE_LENGTH - 1) / MAX_LINE_LENGTH;
        for (int chunk = 0; chunk < chunks; chunk++) {
            int from = chunk * MAX_LINE_LENGTH;
            int to = Math.min(from + MAX_LINE_LENGTH, line.length());
            String label = chunk == 0 ? String.valueOf(lineNumber) : lineNumber + "." + chunk;
            target.append(String.format("%" + LINE_NUMBER_WIDTH + "s\t%s\n", label, line.substring(from, to)));
        }
    }

    private static int countOccurrences(String content, String search) {
        int count = 0;
        int index = content.indexOf(search);
        while (index != -1) {
            count++;
            // 从匹配串末尾继续找：重叠匹配对"替换"来说没有意义，会把次数算多
            index = content.indexOf(search, index + search.length());
        }
        return count;
    }

    /** 字面量替换第一处，不走正则——old_string 里的 . * $ 之类字符必须按字面理解。 */
    private static String replaceFirstLiteral(String content, String target, String replacement) {
        int index = content.indexOf(target);
        return content.substring(0, index) + replacement + content.substring(index + target.length());
    }

    private static String preview(String text) {
        String singleLine = text.replace("\n", "\\n");
        return singleLine.length() <= 80 ? singleLine : singleLine.substring(0, 80) + "...";
    }

    private static String describeEntry(Path child) {
        String name = child.getFileName().toString();
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) {
                return name + "/";
            }
            return name + " (" + attributes.size() + " bytes) ["
                    + formatTimestamp(attributes.lastModifiedTime().toInstant()) + "]";
        } catch (IOException inaccessible) {
            // 单个条目读不到属性不该让整个列目录失败
            return name + " (属性不可读)";
        }
    }

    private static String formatTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static boolean isAbsolutePattern(String pattern) {
        return pattern.startsWith("/") || pattern.startsWith("\\")
                || (pattern.length() >= 2 && pattern.charAt(1) == ':');
    }

    /** 找到第一个通配符所在段的起点，把"固定的基础目录"和"真正的 glob 模式"拆开。 */
    private static int findGlobStart(String pattern) {
        for (int index = 0; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character == '*' || character == '?' || character == '[' || character == '{') {
                String prefix = pattern.substring(0, index);
                int lastSeparator = Math.max(prefix.lastIndexOf('/'), prefix.lastIndexOf('\\'));
                return lastSeparator >= 0 ? lastSeparator + 1 : 0;
            }
        }
        return -1;
    }

    /** 相对路径和文件名都试一遍：'**{@literal /}*.java' 要按相对路径匹配，'*.java' 按文件名匹配。 */
    private static boolean matches(PathMatcher matcher, Path root, Path file) {
        return matcher.matches(root.relativize(file)) || matcher.matches(file.getFileName());
    }

    /** 构建器。 */
    public static final class Builder {

        private PathSandbox sandbox;
        private int maxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB;
        private int defaultLineLimit = DEFAULT_LINE_LIMIT;

        public Builder sandbox(PathSandbox sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        /** 便捷写法，等价于 {@code sandbox(PathSandbox.restrictedTo(dirs))}。 */
        public Builder allowedDirs(String... allowedDirs) {
            this.sandbox = PathSandbox.restrictedTo(allowedDirs);
            return this;
        }

        public Builder maxFileSizeMb(int maxFileSizeMb) {
            this.maxFileSizeMb = maxFileSizeMb;
            return this;
        }

        public Builder defaultLineLimit(int defaultLineLimit) {
            this.defaultLineLimit = defaultLineLimit;
            return this;
        }

        public FileSystemTools build() {
            return new FileSystemTools(this);
        }
    }
}
