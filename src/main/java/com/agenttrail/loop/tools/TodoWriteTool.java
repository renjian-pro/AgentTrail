package com.agenttrail.loop.tools;

import com.agenttrail.loop.model.TodoItem;
import com.agenttrail.loop.model.TodoItem.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 任务清单追踪工具，灵感来自 Claude Code 的同名工具：把智能体对多步骤任务的隐式规划
 * 转成显式、可观测的状态机。
 *
 * <p>每次调用必须提交<b>完整</b>清单，不是相对上一次的增量——模型对"清单现在是什么样"的记忆
 * 并不可靠（尤其经过上下文压缩之后），全量提交把"当前状态"这件事从"模型记不记得住"变成
 * "这一刻愿不愿意完整写一遍"，后者当场可校验、可纠正，前者不是。
 *
 * <p>校验失败以工具结果的形式返回而不是抛异常，跟 {@link JsonToolCallback} 定下的约定一致：
 * 模型下一轮能看到错误、有机会自我纠正，抛出去则整轮对话直接死掉。
 */
public final class TodoWriteTool implements ToolCallback {

    /** 工具名。{@link com.agenttrail.loop.core.ToolCallExecutor} 按这个字面量识别、
     *  独立解析参数发出 {@code TodoProgress} 事件——改名要同步改那边的匹配逻辑。 */
    public static final String TOOL_NAME = "TodoWrite";

    private static final String ITEMS_PARAM = "todos";
    private static final String CONTENT_FIELD = "content";
    private static final String ACTIVE_FORM_FIELD = "activeForm";
    private static final String STATUS_FIELD = "status";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INPUT_SCHEMA = """
            {
              "type" : "object",
              "properties" : {
                "todos" : {
                  "type" : "array",
                  "description" : "完整的任务清单，每次调用都要提交全部任务（已完成/进行中/待办都要包含），不是增量修改",
                  "items" : {
                    "type" : "object",
                    "properties" : {
                      "content" : { "type" : "string", "description" : "任务内容，祈使形式，如\\"运行测试\\"" },
                      "activeForm" : { "type" : "string", "description" : "执行时展示的现在进行时形式，如\\"正在运行测试\\"" },
                      "status" : { "type" : "string", "enum" : [ "pending", "in_progress", "completed" ] }
                    },
                    "required" : [ "content", "activeForm", "status" ]
                  }
                }
              },
              "required" : [ "todos" ]
            }""";

    private static final String DESCRIPTION = """
            创建和管理结构化任务列表，用于跟踪多步骤任务的进度。

            ## 强制执行规则
            1. 收到多步骤任务时，先调用此工具创建任务列表（全部 pending），再执行任何实际操作
            2. 每开始一个任务前，先调用此工具将其标记为 in_progress
            3. 每完成一个任务后，立即调用此工具将其标记为 completed，不要批量更新
            4. 同一时刻只能有一个任务处于 in_progress——先完成当前任务再开始下一个
            5. 每次调用都要提交完整清单，不是相对上一次的增量修改

            ## 使用场景
            复杂的多步骤任务（3 步以上）、用户一次提出多个任务。单一简单任务或信息性问答不需要。
            """;

    private final ToolDefinition definition = ToolDefinition.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(INPUT_SCHEMA)
            .build();

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Parsed parsed = parse(toolInput);
        if (parsed.error() != null) {
            return "Error: " + parsed.error();
        }
        long inProgressCount = parsed.items().stream().filter(item -> item.status() == Status.IN_PROGRESS).count();
        if (inProgressCount > 1) {
            return "Error: 同一时刻只能有一个任务处于 in_progress 状态，本次提交了 %d 个，请先把当前任务标记为 completed 再开始下一个"
                    .formatted(inProgressCount);
        }
        return "任务清单已更新，共 %d 项".formatted(parsed.items().size());
    }

    /**
     * 独立于 {@link #call} 的返回值，重新解析原始参数得到当前快照，供
     * {@link com.agenttrail.loop.core.ToolCallExecutor} 发 {@code TodoProgress} 事件用。
     *
     * <p>进度事件不能依赖工具的返回文本——两者必须各自独立解析同一份原始 JSON，这样工具内部
     * 实现的任何改动都不会悄悄影响前端看到的进度。
     *
     * @return JSON 结构合法就返回快照，哪怕业务校验不通过（比如同时两个 in_progress）——
     *         前端仍然应该看到模型这次真正提交的内容；JSON 本身就不合法时返回空
     */
    public static Optional<List<TodoItem>> parseSnapshot(String toolInput) {
        Parsed parsed = parse(toolInput);
        return parsed.error() == null ? Optional.of(parsed.items()) : Optional.empty();
    }

    private static Parsed parse(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Parsed.error("未提供 todos 参数");
        }
        JsonNode root;
        try {
            root = JSON.readTree(toolInput);
        } catch (Exception malformed) {
            return Parsed.error("参数不是合法的 JSON: " + malformed.getMessage());
        }
        JsonNode todos = root.get(ITEMS_PARAM);
        if (todos == null || !todos.isArray()) {
            return Parsed.error("todos 必须是一个数组");
        }

        List<TodoItem> items = new ArrayList<>();
        for (JsonNode node : todos) {
            String content = textOf(node, CONTENT_FIELD);
            String activeForm = textOf(node, ACTIVE_FORM_FIELD);
            Status status = statusOf(node);
            if (content == null || content.isBlank()) {
                return Parsed.error("每一项任务的 content 都不能为空");
            }
            if (activeForm == null || activeForm.isBlank()) {
                return Parsed.error("每一项任务的 activeForm 都不能为空");
            }
            if (status == null) {
                return Parsed.error("每一项任务的 status 必须是 pending、in_progress 或 completed 之一");
            }
            items.add(new TodoItem(content, activeForm, status));
        }
        return Parsed.ok(items);
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private static Status statusOf(JsonNode node) {
        String raw = textOf(node, STATUS_FIELD);
        if (raw == null) {
            return null;
        }
        try {
            return Status.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private record Parsed(List<TodoItem> items, String error) {
        static Parsed ok(List<TodoItem> items) {
            return new Parsed(items, null);
        }

        static Parsed error(String message) {
            return new Parsed(null, message);
        }
    }
}
