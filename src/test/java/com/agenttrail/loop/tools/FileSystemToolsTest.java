package com.agenttrail.loop.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemToolsTest {

    @TempDir
    Path workspace;

    private FileSystemTools tools;

    @BeforeEach
    void setUp() {
        tools = FileSystemTools.builder().allowedDirs(workspace.toString()).build();
    }

    // ---------- read_file ----------

    @Test
    void readsFileWithCatStyleLineNumbers() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "first\nsecond\n");

        String result = tools.readFile("a.txt", null, null);

        assertThat(result).contains("1\tfirst").contains("2\tsecond");
    }

    @Test
    void paginatesWithOffsetAndLimit() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "l1\nl2\nl3\nl4\n");

        String result = tools.readFile("a.txt", 1, 2);

        assertThat(result).contains("2\tl2").contains("3\tl3").doesNotContain("l1").doesNotContain("l4");
    }

    @Test
    void reportsMissingFileAsAToolErrorInsteadOfThrowing() {
        assertThat(tools.readFile("nope.txt", null, null)).startsWith("Error:");
    }

    /**
     * 踩坑点 #19：Windows 中文环境下文件可能是 GBK，硬编码 UTF-8 读会抛 MalformedInputException。
     * 三级兜底 UTF-8 → GBK → ISO-8859-1，最后一级是单字节编码，必然不抛异常。
     */
    @Test
    void fallsBackToGbkWhenFileIsNotUtf8() throws IOException {
        Files.write(workspace.resolve("gbk.txt"), "中文内容".getBytes(Charset.forName("GBK")));

        assertThat(tools.readFile("gbk.txt", null, null)).contains("中文内容");
    }

    // ---------- write_file ----------

    @Test
    void writesFileAndCreatesMissingParentDirectories() throws IOException {
        String result = tools.writeFile("nested/dir/out.txt", "hello", false);

        assertThat(result).doesNotStartWith("Error:");
        assertThat(workspace.resolve("nested/dir/out.txt")).exists();
        assertThat(Files.readString(workspace.resolve("nested/dir/out.txt"))).isEqualTo("hello");
    }

    /**
     * 踩坑点 #17：参考实现的工具描述写"只能创建新文件，已存在会报错"，实现却是
     * {@code CREATE + TRUNCATE_EXISTING}——永远静默覆盖，和描述完全相反。
     * 这里选择让实现服从描述：默认拒绝覆盖，要覆盖必须显式传 overwrite=true。
     */
    @Test
    void refusesToSilentlyOverwriteAnExistingFile() throws IOException {
        Files.writeString(workspace.resolve("exists.txt"), "original");

        String result = tools.writeFile("exists.txt", "replacement", false);

        assertThat(result).startsWith("Error:").contains("overwrite");
        assertThat(Files.readString(workspace.resolve("exists.txt"))).isEqualTo("original");
    }

    @Test
    void overwritesOnlyWhenExplicitlyAsked() throws IOException {
        Files.writeString(workspace.resolve("exists.txt"), "original");

        tools.writeFile("exists.txt", "replacement", true);

        assertThat(Files.readString(workspace.resolve("exists.txt"))).isEqualTo("replacement");
    }

    // ---------- edit_file ----------

    @Test
    void editsTheUniqueOccurrence() throws IOException {
        Files.writeString(workspace.resolve("code.txt"), "alpha\nbeta\n");

        String result = tools.editFile("code.txt", "beta", "gamma", false);

        assertThat(result).doesNotStartWith("Error:");
        assertThat(Files.readString(workspace.resolve("code.txt"))).isEqualTo("alpha\ngamma\n");
    }

    /**
     * 踩坑点 #18（本票验收标准）：命中多处时拒绝执行。
     * 无脑替换第一个可能改错地方，无脑全替换可能误伤——两种都是"静默的错误批量操作"，
     * 所以直接报错，逼调用方要么补上下文让匹配唯一，要么显式声明 replace_all。
     */
    @Test
    void refusesToEditWhenTheTargetTextIsNotUnique() throws IOException {
        Files.writeString(workspace.resolve("code.txt"), "x = 1\ny = 1\n");

        String result = tools.editFile("code.txt", "= 1", "= 2", false);

        assertThat(result).startsWith("Error:").contains("2").contains("replace_all");
        assertThat(Files.readString(workspace.resolve("code.txt"))).isEqualTo("x = 1\ny = 1\n");
    }

    @Test
    void replacesEveryOccurrenceWhenReplaceAllIsRequested() throws IOException {
        Files.writeString(workspace.resolve("code.txt"), "x = 1\ny = 1\n");

        tools.editFile("code.txt", "= 1", "= 2", true);

        assertThat(Files.readString(workspace.resolve("code.txt"))).isEqualTo("x = 2\ny = 2\n");
    }

    @Test
    void reportsTextThatIsNotPresentAtAll() throws IOException {
        Files.writeString(workspace.resolve("code.txt"), "alpha\n");

        assertThat(tools.editFile("code.txt", "missing", "x", false)).startsWith("Error:");
    }

    @Test
    void rejectsAnEditThatWouldChangeNothing() throws IOException {
        Files.writeString(workspace.resolve("code.txt"), "alpha\n");

        assertThat(tools.editFile("code.txt", "alpha", "alpha", false)).startsWith("Error:");
    }

    /**
     * 参考实现的 {@code edit_file} 用 {@code Files.readString}/{@code Files.writeString}（都是 UTF-8），
     * 而 {@code read_file} 有三级编码兜底——同一个 GBK 文件读得出来却编辑不了，编辑成功的话
     * 还会被静默改写成 UTF-8。这里让编辑走同一套兜底，并按原编码写回。
     */
    @Test
    void editsANonUtf8FileAndKeepsItsOriginalEncoding() throws IOException {
        Charset gbk = Charset.forName("GBK");
        Files.write(workspace.resolve("gbk.txt"), "原始内容".getBytes(gbk));

        String result = tools.editFile("gbk.txt", "原始", "新的", false);

        assertThat(result).doesNotStartWith("Error:");
        assertThat(Files.readAllBytes(workspace.resolve("gbk.txt"))).isEqualTo("新的内容".getBytes(gbk));
    }

    // ---------- list_files / glob_files ----------

    @Test
    void listsDirectoryEntriesMarkingSubdirectories() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.createDirectory(workspace.resolve("sub"));

        String result = tools.listFiles(".");

        assertThat(result).contains("a.txt").contains("sub/");
    }

    @Test
    void globsFilesRecursively() throws IOException {
        Files.createDirectories(workspace.resolve("src/main"));
        Files.writeString(workspace.resolve("src/main/App.java"), "class App {}");
        Files.writeString(workspace.resolve("src/main/notes.txt"), "notes");

        String result = tools.globFiles("**/*.java");

        assertThat(result).contains("App.java").doesNotContain("notes.txt");
    }

    // ---------- 沙箱边界（本票验收标准）----------

    @Test
    void refusesToReadOutsideTheWhitelistedDirectory() throws IOException {
        Path outside = Files.writeString(workspace.getParent().resolve("outside.txt"), "secret");

        assertThat(tools.readFile(outside.toString(), null, null))
                .startsWith("Error:")
                .contains("不在允许访问的目录内");
    }

    @Test
    void refusesToEscapeTheWhitelistWithDotDotSegments() throws IOException {
        Files.writeString(workspace.getParent().resolve("outside.txt"), "secret");

        assertThat(tools.readFile("../outside.txt", null, null)).startsWith("Error:");
    }

    @Test
    void refusesToWriteOutsideTheWhitelistedDirectory() {
        Path outside = workspace.getParent().resolve("written-outside.txt");

        assertThat(tools.writeFile(outside.toString(), "x", true)).startsWith("Error:");
        assertThat(outside).doesNotExist();
    }

    @Test
    void refusesToEditOutsideTheWhitelistedDirectory() throws IOException {
        Path outside = Files.writeString(workspace.getParent().resolve("outside.txt"), "secret");

        assertThat(tools.editFile(outside.toString(), "secret", "leaked", false)).startsWith("Error:");
        assertThat(Files.readString(outside)).isEqualTo("secret");
    }

    @Test
    void refusesToListOutsideTheWhitelistedDirectory() {
        assertThat(tools.listFiles(workspace.getParent().toString())).startsWith("Error:");
    }

    @Test
    void refusesToGlobOutsideTheWhitelistedDirectory() throws IOException {
        Files.writeString(workspace.getParent().resolve("outside.java"), "class X {}");

        // 模式必须按字符串拼：Windows 下 Path#resolve("*.java") 会因为 '*' 是非法路径字符而抛异常
        String outsidePattern = workspace.getParent() + java.io.File.separator + "*.java";

        assertThat(tools.globFiles(outsidePattern))
                .startsWith("Error:")
                .doesNotContain("outside.java");
    }

    // ---------- ToolCallback 包装 ----------

    @Test
    void exposesTheFiveFileSystemToolsAsToolCallbacks() {
        List<String> names = tools.toolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                "read_file", "write_file", "edit_file", "list_files", "glob_files");
    }

    @Test
    void toolCallbacksParseJsonArgumentsAndRunTheOperation() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ToolCallback readFile = callbackNamed("read_file");

        assertThat(readFile.call("{\"path\":\"a.txt\"}")).contains("hello");
    }

    /** 工具内部的异常一律吞成"工具结果"字符串，和 ToolCallExecutor 的既有约定保持一致。 */
    @Test
    void toolCallbacksReturnErrorsAsResultsRatherThanThrowing() {
        ToolCallback readFile = callbackNamed("read_file");

        assertThat(readFile.call("{\"path\":\"../outside.txt\"}")).startsWith("Error:");
        assertThat(readFile.call("not json at all")).startsWith("Error:");
    }

    /** 工具描述里声明的参数必须真的出现在 schema 里，否则模型不知道能传什么。 */
    @Test
    void declaresInputSchemaForEveryParameter() {
        ToolDefinition editFile = callbackNamed("edit_file").getToolDefinition();

        assertThat(editFile.inputSchema())
                .contains("path").contains("old_string").contains("new_string").contains("replace_all");
        assertThat(editFile.description()).isNotBlank();
    }

    private ToolCallback callbackNamed(String name) {
        Map<String, ToolCallback> byName = tools.toolCallbacks().stream()
                .collect(java.util.stream.Collectors.toMap(c -> c.getToolDefinition().name(), c -> c));
        return byName.get(name);
    }
}
