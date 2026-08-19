package com.agenttrail.capability.ppt.application;

import com.agenttrail.capability.ppt.PptRunStatus;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptTask;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * PPT 会话消息的状态感知路由。调用者只需要提供最近任务和用户消息，不需要复制状态判断顺序。
 *
 * <p>状态优先解决了旧关键词识别器最危险的行为：等待澄清时，一句普通回答不再因为没有命中
 * “修改/继续”而落到 CREATE。取消使用整句命令模式，避免把“订单取消率分析”误判为取消任务。
 */
public final class PptMessageRouter {

    private static final Pattern CANCEL_COMMAND = Pattern.compile(
            "^(请)?(帮我)?(取消|停止|终止)(这次|当前|这个)?(ppt)?(生成|任务)?(吧|了)?$",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> RESUME_KEYWORDS = List.of(
            "继续生成", "恢复生成", "接着生成", "继续之前", "重试", "继续执行");
    private static final List<String> MODIFY_KEYWORDS = List.of(
            "修改", "调整", "改成", "换成", "改一下", "重做第", "重新生成第", "删除第", "增加一页");

    public Decision route(Optional<PptTask> latestTask, String message) {
        PptTask latest = latestTask.orElse(null);
        String normalized = normalize(message);

        if (latest != null && isCancellable(latest) && CANCEL_COMMAND.matcher(normalized).matches()) {
            return new Decision(Action.CANCEL, latest.id(), "explicit-cancel-command");
        }
        if (latest != null && latest.status() == PptState.AWAITING_INPUT) {
            return new Decision(Action.ANSWER, latest.id(), "task-awaiting-input");
        }
        if (latest != null && isResumable(latest) && containsAny(normalized, RESUME_KEYWORDS)) {
            return new Decision(Action.RESUME, latest.id(), "unfinished-task-and-resume-keyword");
        }
        if (latest != null && latest.status() != PptState.CANCELLED
                && containsAny(normalized, MODIFY_KEYWORDS)) {
            return new Decision(Action.MODIFY, latest.id(), latest.status() == PptState.SUCCESS
                    ? "successful-task-and-modify-keyword"
                    : "active-task-and-modify-keyword");
        }
        return new Decision(Action.CREATE, null, "create-fallback");
    }

    private static boolean isCancellable(PptTask task) {
        return task.status() != PptState.SUCCESS && task.status() != PptState.CANCELLED;
    }

    private static boolean isResumable(PptTask task) {
        return task.status() != PptState.SUCCESS
                && task.status() != PptState.CANCELLED
                && task.status() != PptState.AWAITING_INPUT
                && task.runStatus() != PptRunStatus.RUNNING;
    }

    private static boolean containsAny(String value, List<String> keywords) {
        return keywords.stream().anyMatch(value::contains);
    }

    private static String normalize(String message) {
        if (message == null) return "";
        return message.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    public enum Action {
        CREATE,
        ANSWER,
        MODIFY,
        RESUME,
        CANCEL
    }

    public record Decision(Action action, Long taskId, String reason) {
    }
}
