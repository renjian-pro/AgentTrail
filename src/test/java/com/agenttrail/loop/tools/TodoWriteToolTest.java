package com.agenttrail.loop.tools;

import com.agenttrail.loop.model.TodoItem;
import com.agenttrail.loop.model.TodoItem.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TodoWriteToolTest {

    private final TodoWriteTool tool = new TodoWriteTool();

    @Test
    void acceptsAWellFormedFullList() {
        String result = tool.call("""
                {"todos":[
                  {"content":"运行测试","activeForm":"正在运行测试","status":"pending"}
                ]}""");

        assertThat(result).doesNotContain("Error");
    }

    /** 核心校验规则：同一时刻只能有一个任务处于 in_progress。 */
    @Test
    void rejectsMoreThanOneInProgressTaskWithAClearError() {
        String result = tool.call("""
                {"todos":[
                  {"content":"任务一","activeForm":"正在做任务一","status":"in_progress"},
                  {"content":"任务二","activeForm":"正在做任务二","status":"in_progress"}
                ]}""");

        assertThat(result).contains("Error").contains("in_progress");
    }

    @Test
    void allowsExactlyOneInProgressTask() {
        String result = tool.call("""
                {"todos":[
                  {"content":"任务一","activeForm":"正在做任务一","status":"completed"},
                  {"content":"任务二","activeForm":"正在做任务二","status":"in_progress"},
                  {"content":"任务三","activeForm":"正在做任务三","status":"pending"}
                ]}""");

        assertThat(result).doesNotContain("Error");
    }

    @Test
    void rejectsATaskWithBlankContent() {
        String result = tool.call("""
                {"todos":[{"content":"  ","activeForm":"正在做","status":"pending"}]}""");

        assertThat(result).contains("Error").contains("content");
    }

    @Test
    void rejectsATaskWithBlankActiveForm() {
        String result = tool.call("""
                {"todos":[{"content":"运行测试","activeForm":"","status":"pending"}]}""");

        assertThat(result).contains("Error").contains("activeForm");
    }

    @Test
    void rejectsATaskWithAnInvalidStatusValue() {
        String result = tool.call("""
                {"todos":[{"content":"运行测试","activeForm":"正在运行测试","status":"done"}]}""");

        assertThat(result).contains("Error").contains("status");
    }

    @Test
    void rejectsMissingTodosField() {
        String result = tool.call("{}");

        assertThat(result).contains("Error");
    }

    @Test
    void degradesMalformedJsonToAnErrorResultInsteadOfThrowing() {
        String result = tool.call("{not json");

        assertThat(result).contains("Error");
    }

    // ==================== parseSnapshot ====================

    @Test
    void parseSnapshotReturnsTheSubmittedItemsWhenStructurallyValid() {
        Optional<List<TodoItem>> snapshot = TodoWriteTool.parseSnapshot("""
                {"todos":[
                  {"content":"任务一","activeForm":"正在做任务一","status":"in_progress"}
                ]}""");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get()).containsExactly(new TodoItem("任务一", "正在做任务一", Status.IN_PROGRESS));
    }

    /** 快照即使业务校验（单一 in_progress）不通过，也要如实反映模型这次提交了什么——前端展示不能是空白。 */
    @Test
    void parseSnapshotStillReturnsItemsWhenBusinessValidationWouldFail() {
        Optional<List<TodoItem>> snapshot = TodoWriteTool.parseSnapshot("""
                {"todos":[
                  {"content":"任务一","activeForm":"正在做任务一","status":"in_progress"},
                  {"content":"任务二","activeForm":"正在做任务二","status":"in_progress"}
                ]}""");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get()).hasSize(2);
    }

    @Test
    void parseSnapshotIsEmptyForMalformedJson() {
        assertThat(TodoWriteTool.parseSnapshot("{not json")).isEmpty();
    }
}
