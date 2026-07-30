package com.agenttrail.loop.tools;

import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * bash 工具：把 {@link ShellSessionManager} 的伪持久 shell 暴露成一个 ToolCallback。
 *
 * <p>本类只做三件事：会话重启、参数兜底、结果格式化。真正的执行语义都在
 * {@link ShellSessionManager} 里（包括"为什么不维护长活进程"这个核心取舍，见那边的类注释）。
 *
 * <p><b>安全边界</b>：这个工具拥有和 JVM 进程同等的系统权限，**没有目录白名单**——
 * 沙箱能挡住 {@code read_file ../../etc/passwd}，挡不住 {@code cat ../../etc/passwd}。
 * 所以踩坑点 #41 的结论在这里是：文件类工具可以靠白名单对外暴露，bash 不行，
 * 它对外暴露的前提是有 PreToolUse Hook 做审批，而不是工具自己判断"要不要执行"。
 */
public final class BashTool {

    private static final String DEFAULT_SESSION_ID = "default";

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private final ShellSessionManager sessionManager;
    private final String sessionId;
    private final ToolCallback toolCallback;

    private BashTool(Builder builder) {
        this.sessionManager = builder.sessionManager != null
                ? builder.sessionManager
                : ShellSessionManager.builder().build();
        this.sessionId = builder.sessionId;
        this.toolCallback = buildCallback();
    }

    public static Builder builder() {
        return new Builder();
    }

    public ToolCallback toolCallback() {
        return toolCallback;
    }

    /**
     * 执行一条命令。
     *
     * @param restart   true 时先把会话状态清掉（回到初始目录）
     * @param timeoutMs 本次调用的超时时间；参考实现在 schema 里声明了这个参数却从没用过，
     *                  声明了就要兑现，否则又是一处文档与实现不一致
     */
    public String execute(String command, Boolean restart, Long timeoutMs) {
        if (Boolean.TRUE.equals(restart)) {
            sessionManager.reset(sessionId);
        }
        if (command == null || command.isBlank()) {
            return "Error: command 不能为空";
        }

        String directoryBefore = sessionManager.workingDirectory(sessionId);
        Duration timeout = (timeoutMs != null && timeoutMs > 0) ? Duration.ofMillis(timeoutMs) : null;
        ShellSessionManager.CommandResult result = sessionManager.execute(sessionId, command, timeout);
        return format(result, directoryBefore);
    }

    /**
     * 结果格式化。
     *
     * <p>只在"有内容"时才拼进去：空的 STDERR 段、恒等于 0 的退出码、没变过的工作目录，
     * 每一条都是每次调用都要付的上下文成本，攒起来相当可观。
     */
    private String format(ShellSessionManager.CommandResult result, String directoryBefore) {
        List<String> parts = new ArrayList<>();
        if (!result.stdout().isBlank()) {
            parts.add(result.stdout().stripTrailing());
        }
        if (!result.stderr().isBlank()) {
            parts.add("STDERR:\n" + result.stderr().stripTrailing());
        }
        if (result.timedOut()) {
            parts.add("[命令超时，已被强制终止]");
        } else if (result.exitCode() != 0) {
            parts.add("[退出码: " + result.exitCode() + "]");
        }
        if (!result.workingDirectory().equals(directoryBefore)) {
            // 目录变了要告诉模型，否则它下一条命令还按老目录写相对路径
            parts.add("[工作目录已切换到: " + result.workingDirectory() + "]");
        }
        return parts.isEmpty() ? "（命令执行成功，无输出）" : String.join("\n", parts);
    }

    private ToolCallback buildCallback() {
        return new JsonToolCallback("bash", description(), """
                {"type":"object","properties":{\
                "command":{"type":"string","description":"【必填】要执行的命令，可以是多行"},\
                "restart":{"type":"boolean","description":"执行前是否重置会话（回到初始工作目录），默认 false"},\
                "timeout_ms":{"type":"integer","description":"本次执行的超时毫秒数，不传用默认值"}},\
                "required":["command"]}""",
                args -> execute(args.text("command"), args.flag("restart"), args.longValue("timeout_ms")));
    }

    /**
     * 工具描述按当前操作系统动态生成。
     *
     * <p>模型并不知道自己跑在 Windows 还是 Linux 上，描述里不写清楚，它会按训练数据里的多数派
     * 生成 Unix 命令，在 Windows 上一条条失败再重试，白白烧掉好几轮。
     */
    private static String description() {
        String platformNotes = WINDOWS ? """
                **当前是 Windows (cmd.exe)，请用 Windows 命令：**
                - 用 type 代替 cat，dir 代替 ls，del 代替 rm，move 代替 mv，findstr 代替 grep
                - mkdir 会自动创建父目录，**不要加 -p**，那会建出一个名叫 '-p' 的目录
                - 多条命令用 && 或 & 连接""" : """
                **当前是 Unix/Linux/macOS (bash)：**
                - 标准命令均可用；多条命令用 && 或 ; 连接""";

        return """
                在一个"伪持久"的 shell 会话里执行命令。

                %s

                会话语义（重要）:
                - **只有工作目录跨调用保留**：cd 之后，后续每次调用都从新目录开始
                - 环境变量、shell 函数、后台作业都不保留——每次执行都是一个全新的子进程
                - restart=true 可以把工作目录重置回初始目录

                工具优先级:
                - 本工具是最后手段。读文件用 read_file、改文件用 edit_file、写文件用 write_file、
                  找文件用 glob_files、搜内容用 grep，都不要用本工具绕过去
                - 这些专用工具有目录白名单和唯一性校验保护，bash 没有
                """.formatted(platformNotes);
    }

    /** 构建器。 */
    public static final class Builder {

        private ShellSessionManager sessionManager;
        private String sessionId = DEFAULT_SESSION_ID;

        public Builder sessionManager(ShellSessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        /** 会话标识。多 Agent / SubAgent 场景下各用各的，目录状态才不会互相串。 */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public BashTool build() {
            return new BashTool(this);
        }
    }
}
