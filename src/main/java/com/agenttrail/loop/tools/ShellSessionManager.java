package com.agenttrail.loop.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 伪持久 shell 的会话状态管理（踩坑点 #20）。
 *
 * <p><b>为什么是"伪"持久，而不是真维护一个长活进程</b>——想让 Agent 感觉像在操作一个一直开着的
 * 终端（{@code cd} 完之后后续命令都在新目录里跑），有两条路：
 * <ol>
 *   <li>真起一个长活 shell 进程，把命令写进它的 stdin，从 stdout 读回结果；
 *   <li>每次都起一个新子进程，只在 Java 侧记住"上次在哪个目录"，执行前把 {@code cd} 拼上去。
 * </ol>
 * 这里选第二条，代价小得多而用户感知一样：
 * <ul>
 *   <li><b>没有可靠的"命令结束"信号</b>：长活 shell 的 stdout 是一条连续的流，要判断某条命令
 *       输出完了，得自己往命令里塞哨兵串再解析，或者上 pty——复杂度全在这一步，而且哨兵串
 *       出现在用户输出里就会误判。一次一进程，进程退出就是天然的结束信号，退出码还是白送的。
 *   <li><b>资源与故障隔离</b>：长活进程要按会话回收，会话泄漏就是进程泄漏；一条卡死的命令会
 *       污染整个会话，后续所有命令跟着完蛋。一次一进程，超时直接 kill，炸掉的只是这一次。
 *   <li><b>状态可持久化</b>：{@code lastDirectory} 只是一个字符串，能随会话一起落库、进程重启后
 *       原样恢复、甚至换一个实例接着跑；一个长活 OS 进程做不到这些。
 * </ul>
 * 代价要说清楚：**环境变量、shell 函数、后台作业都不会跨调用保留**，只有工作目录保留。
 * 所以工具描述里绝不能宣称"环境变量持久化"——那正是参考实现犯的文档/实现不一致的错（同 #17）。
 *
 * <p><b>目录是怎么保持的</b>：执行前拼 {@code cd} 前缀，执行完让 shell 把"退出码 + 当前目录"
 * 写进一个临时状态文件，我们再读回来。参考实现的做法是"命令以 {@code cd } 开头就自己拿 File
 * 拼一个新路径"，有两个毛病：{@code mkdir x && cd x} 这类组合命令跟不上；{@code cd ..} 拼出来的
 * 是没归一化的 {@code /a/b/..}，越滚越长。让 shell 自己报告它在哪，这两个问题一起消失。
 *
 * <p><b>为什么状态走文件而不是走 stdout 里的哨兵串</b>：哨兵串会污染命令的真实输出，
 * 而且用户命令里正好打印出同样的字符串时就会误判。写文件对 stdout 零干扰。
 *
 * <p><b>为什么不把命令写成临时脚本再执行</b>（这是最初的实现，被实测推翻）：临时脚本本来更优雅
 * ——批处理文件逐行解析，{@code %errorlevel%} 能直接拿到真值，也不用跟命令行的引号规则搏斗。
 * 但在装了终端安全软件的 Windows 上，每个**新创建的 .cmd 文件**在执行前都会被完整扫描一遍：
 * 实测单次执行从 90ms 涨到 7.4s，而且会间歇性地
 * {@code CreateProcess error=5（拒绝访问）}——文件还被扫描器占着。改成拼命令行内联执行后，
 * 这两个问题一起消失。这也是"优雅的方案要拿真实环境验证"的一个具体案例。
 */
public final class ShellSessionManager {

    private static final Logger log = LoggerFactory.getLogger(ShellSessionManager.class);

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    /** 默认上限按"模型上下文预算"定，而不是按内存定：500 行 / 20KB 已经是很重的一条工具结果了。 */
    private static final int DEFAULT_MAX_LINES = 500;
    private static final int DEFAULT_MAX_BYTES = 20_000;
    /** 读输出的线程最多再等这么久：子进程被强杀后可能还有孙子进程握着管道，不能跟着挂死。 */
    private static final long READER_JOIN_MILLIS = 2000;

    private final Path initialDirectory;
    private final Duration timeout;
    private final Charset charset;
    private final OutputTruncator truncator;
    private final Map<String, ShellSession> sessions = new ConcurrentHashMap<>();

    private ShellSessionManager(Builder builder) {
        this.initialDirectory = canonicalize(builder.initialDirectory);
        this.timeout = builder.timeout;
        this.charset = builder.charset != null ? builder.charset : defaultShellCharset();
        this.truncator = new OutputTruncator(builder.maxLines, builder.maxBytes, this.charset);
    }

    public static Builder builder() {
        return new Builder();
    }

    public CommandResult execute(String sessionId, String command) {
        return execute(sessionId, command, null);
    }

    /**
     * 在指定会话里执行一条命令。
     *
     * @param sessionId       会话标识，不同会话的目录状态互不影响
     * @param command         命令原文
     * @param timeoutOverride 本次调用的超时时间，null 表示用默认值
     */
    public CommandResult execute(String sessionId, String command, Duration timeoutOverride) {
        ShellSession session = sessions.computeIfAbsent(sessionId,
                id -> new ShellSession(initialDirectory.toString()));

        if (command == null || command.isBlank()) {
            return new CommandResult(-1, "", "Error: 命令不能为空", session.lastDirectory, false);
        }

        Path stateFile = null;
        try {
            stateFile = Files.createTempFile("agenttrail-shell-state-", ".txt");
            List<String> invocation = buildInvocation(command, session.lastDirectory, stateFile);
            return run(session, invocation, stateFile,
                    timeoutOverride != null ? timeoutOverride : timeout);
        } catch (IOException failure) {
            log.error("会话 {} 执行命令失败：{}", sessionId, failure.getMessage(), failure);
            return new CommandResult(-1, "", "Error: " + failure.getMessage(),
                    session.lastDirectory, false);
        } finally {
            deleteQuietly(stateFile);
        }
    }

    /** 会话当前的工作目录；会话不存在时返回初始目录，且**不会**因为一次查询就创建会话。 */
    public String workingDirectory(String sessionId) {
        ShellSession session = sessions.get(sessionId);
        return session != null ? session.lastDirectory : initialDirectory.toString();
    }

    /** 丢弃会话状态。因为状态只是一个字符串，"重启 shell"就是把它删掉这么简单。 */
    public void reset(String sessionId) {
        sessions.remove(sessionId);
    }

    // ------------------------------------------------------------------
    // 命令行拼装
    // ------------------------------------------------------------------

    /**
     * 拼出实际要执行的命令行：先 {@code cd} 进上次的目录，跑命令，再把"退出码 + 当前目录"
     * 写进状态文件。
     *
     * <p>Windows 这一段有个必须绕开的坑：{@code cmd /c "命令 & echo %errorlevel%"} 里的
     * {@code %errorlevel%} 在**解析期**就被展开了，拿到的是命令执行**前**的值（几乎总是 0）。
     * 常见解法是开延迟展开（{@code cmd /v:on} 配 {@code !errorlevel!}），但那样用户命令里的
     * {@code !} 会被吃掉。这里用 {@code call echo %^errorlevel%}：插入符让变量名在第一次解析时
     * 不成立，{@code call} 触发的第二次解析才把它展开成真值，用户命令则完全不受影响。
     */
    private List<String> buildInvocation(String command, String workingDirectory, Path stateFile) {
        if (WINDOWS) {
            // cmd 的一条命令行没法带换行，多行命令用 & 串起来
            String singleLine = command.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .reduce((left, right) -> left + " & " + right)
                    .orElse("");
            String line = "cd /d \"%s\" && %s & call echo %%^errorlevel%% > \"%s\" & cd >> \"%s\""
                    .formatted(workingDirectory, singleLine, stateFile, stateFile);
            return List.of("cmd.exe", "/c", line);
        }

        String script = """
                cd "%s" || exit 1
                %s
                __agenttrail_rc=$?
                { echo "$__agenttrail_rc"; pwd; } > "%s"
                exit $__agenttrail_rc
                """.formatted(workingDirectory, command, stateFile);
        return List.of("bash", "-c", script);
    }

    // ------------------------------------------------------------------
    // 进程执行
    // ------------------------------------------------------------------

    /**
     * 子进程默认继承整个 JVM 进程的环境变量（Java {@link ProcessBuilder} 的默认行为），数据库密码、
     * 模型 API Key 这些如果通过环境变量注入配置（见 {@code application.yml} 里
     * {@code OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION} 这类 {@code ${VAR}} 占位符），模型执行
     * {@code env}/{@code set} 类命令就能把它们原样打印出来——凭据隔离审查（ticket 09）确认过
     * 这是真实可利用的风险，不是假设性问题。只保留 shell 正常工作必需的变量，其余一律清空。
     */
    private static final List<String> ENV_WHITELIST = WINDOWS
            ? List.of("PATH", "PATHEXT", "SYSTEMROOT", "SYSTEMDRIVE", "COMSPEC", "WINDIR",
                    "TEMP", "TMP", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "NUMBER_OF_PROCESSORS")
            : List.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "SHELL");

    private static void sanitizeEnvironment(Map<String, String> childEnvironment) {
        childEnvironment.keySet().removeIf(key -> ENV_WHITELIST.stream().noneMatch(key::equalsIgnoreCase));
    }

    private CommandResult run(ShellSession session, List<String> invocation, Path stateFile,
                              Duration effectiveTimeout) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(invocation);
        sanitizeEnvironment(processBuilder.environment());
        Process process = processBuilder.start();
        // 立刻关掉子进程的 stdin：交互式命令会读到 EOF 直接退出，而不是永远等一个不会来的输入
        process.getOutputStream().close();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        // stdout 和 stderr 必须并发读：管道缓冲区是有限的，只读一路的话另一路写满就把子进程
        // 阻塞在 write 上，父进程又在 waitFor 上等它退出，直接死锁。
        // 用虚拟线程：这正是"阻塞在 IO 上、生命周期跟着一次调用走"的旁路任务，
        // 没必要为它占用平台线程池的名额（踩坑点 #73）。
        Thread outReader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout));
        Thread errReader = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr));

        boolean finished;
        try {
            finished = process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                log.warn("命令超过 {}ms 未结束，强制终止", effectiveTimeout.toMillis());
                // 先杀子孙再杀本体：shell 只是个壳，真正在跑的是它拉起来的进程，
                // 只杀 shell 会留下一串孤儿进程继续占着管道和 CPU
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor();
            }
            outReader.join(READER_JOIN_MILLIS);
            errReader.join(READER_JOIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return new CommandResult(-1, "", "Error: 命令执行被中断", session.lastDirectory, false);
        }

        int processExitCode = finished ? process.exitValue() : -1;
        int exitCode = processExitCode;
        if (finished) {
            ShellState state = readState(stateFile);
            if (state.exitCode() != null) {
                // 状态文件里的退出码才是命令自己的；进程退出码是命令行最后一段（写状态）的
                exitCode = state.exitCode();
            }
            if (state.workingDirectory() != null) {
                session.lastDirectory = state.workingDirectory();
            }
        }

        return new CommandResult(exitCode,
                truncator.truncate(stdout.toString()),
                truncator.truncate(stderr.toString()),
                session.lastDirectory,
                !finished);
    }

    private void drain(InputStream stream, StringBuilder target) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                target.append(line).append('\n');
            }
        } catch (IOException closed) {
            // 进程被强杀时管道会提前关闭，此时已经读到的内容仍然有价值，不当作失败
            log.debug("读取子进程输出提前结束：{}", closed.getMessage());
        }
    }

    /**
     * 读回状态文件。
     *
     * <p>状态文件可能是空的——命令自己调了 {@code exit}（shell 直接终止，后面的写状态没机会跑）、
     * 或者命令超时被杀。这两种情况都退回用进程退出码，工作目录保持不动，而不是把状态清空。
     */
    private ShellState readState(Path stateFile) {
        try {
            List<String> lines = Files.readAllLines(stateFile, charset).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
            Integer exitCode = null;
            String directory = null;
            if (!lines.isEmpty()) {
                try {
                    exitCode = Integer.valueOf(lines.get(0));
                } catch (NumberFormatException notANumber) {
                    log.debug("状态文件里的退出码无法解析：{}", lines.get(0));
                }
            }
            if (lines.size() > 1) {
                Path candidate = Paths.get(lines.get(1)).normalize();
                if (Files.isDirectory(candidate)) {
                    directory = candidate.toString();
                }
            }
            return new ShellState(exitCode, directory);
        } catch (IOException | RuntimeException unreadable) {
            log.debug("读取 shell 状态文件失败：{}", unreadable.toString());
            return new ShellState(null, null);
        }
    }

    /**
     * 子进程输出用哪种编码解。
     *
     * <p>Java 18 起 {@code file.encoding} 默认是 UTF-8，但 Windows 上 cmd.exe 的内置命令仍然按
     * **活动代码页**（中文环境是 GBK）输出，两边不一致的结果就是中文路径全是乱码——
     * 参考实现把默认字符集写成 UTF-8，正好踩在这上面。{@code native.encoding} 才是当前平台
     * 的本地编码，用它来解子进程的输出。
     */
    private static Charset defaultShellCharset() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null && !nativeEncoding.isBlank()) {
            try {
                return Charset.forName(nativeEncoding);
            } catch (RuntimeException unsupported) {
                log.debug("native.encoding={} 不被支持，回退 UTF-8", nativeEncoding);
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Path canonicalize(String directory) {
        Path path = (directory == null || directory.isBlank())
                ? Paths.get(System.getProperty("user.dir"))
                : Paths.get(directory);
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException missing) {
            return absolute;
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException | UncheckedIOException undeletable) {
            log.debug("临时文件删除失败：{}", file);
        }
    }

    // ------------------------------------------------------------------
    // 数据结构
    // ------------------------------------------------------------------

    /** 一次命令执行的结果。 */
    public record CommandResult(int exitCode, String stdout, String stderr,
                                String workingDirectory, boolean timedOut) {

        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }

    /** 从状态文件里读回来的东西；字段可能为 null，表示这一项没读到。 */
    private record ShellState(Integer exitCode, String workingDirectory) {
    }

    /**
     * 会话状态。整个"持久 shell"就只有这一个字段——这正是伪持久设计的全部代价与全部收益。
     * 用 volatile 是因为同一个会话可能被 loop 的不同线程先后碰到。
     */
    private static final class ShellSession {

        private volatile String lastDirectory;

        private ShellSession(String lastDirectory) {
            this.lastDirectory = lastDirectory;
        }
    }

    /** 构建器。 */
    public static final class Builder {

        private String initialDirectory;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxLines = DEFAULT_MAX_LINES;
        private int maxBytes = DEFAULT_MAX_BYTES;
        private Charset charset;

        public Builder initialDirectory(String initialDirectory) {
            this.initialDirectory = initialDirectory;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxLines(int maxLines) {
            this.maxLines = maxLines;
            return this;
        }

        public Builder maxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
            return this;
        }

        public Builder charset(Charset charset) {
            this.charset = charset;
            return this;
        }

        public ShellSessionManager build() {
            return new ShellSessionManager(this);
        }
    }
}
