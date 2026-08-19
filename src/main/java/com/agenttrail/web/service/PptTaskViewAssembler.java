package com.agenttrail.web.service;

import com.agenttrail.capability.ppt.PptCheckpointEvent;
import com.agenttrail.capability.ppt.PptFailure;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationService;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptTask;
import com.agenttrail.capability.ppt.PptWarning;
import com.agenttrail.web.dto.PptGenerationResponse;
import com.agenttrail.web.dto.PptTaskCapabilities;
import com.agenttrail.web.dto.PptTaskView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把任务、上下文和持久化事件装配成前端唯一任务视图，集中所有展示映射与 capability 推导。 */
@Service
public class PptTaskViewAssembler {

    private static final Logger log = LoggerFactory.getLogger(PptTaskViewAssembler.class);
    private static final Pattern COUNTER = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private static final List<PptState> PIPELINE = List.of(
            PptState.INIT, PptState.CLARIFY, PptState.REQUIREMENT, PptState.SEARCH,
            PptState.VISUAL_PLAN, PptState.TEMPLATE, PptState.OUTLINE, PptState.SCHEMA,
            PptState.IMAGE, PptState.RENDER, PptState.VERIFY);
    private static final Map<PptState, String> STAGE_LABELS = stageLabels();

    private final PptGenerationService generationService;

    public PptTaskViewAssembler(PptGenerationService generationService) {
        this.generationService = generationService;
    }

    public PptGenerationResponse response(String userId, long taskId) {
        PptTask task = (userId == null ? generationService.describe(taskId)
                : generationService.describe(userId, taskId))
                .orElseThrow(() -> new IllegalStateException("PPT 任务不存在: " + taskId));
        PptGenerationContext context = safeContext(task);
        String downloadUrl = task.status() == PptState.SUCCESS
                && (context != null && context.artifactRef() != null || hasOutputFile(userId, taskId))
                ? "/agent/v1/ppt/" + taskId + "/download"
                : null;
        PptTaskView view = view(task, context);
        log.debug("PPT task view assembled taskId={} pipelineState={} runStatus={} revision={} progressEventCount={}",
                taskId, task.status(), task.runStatus(), task.revision(), view.progressEvents().size());
        return new PptGenerationResponse(taskId, task.status(), task.errorMsg(), downloadUrl,
                generationService.pendingClarifyingQuestionOf(userId, taskId), view);
    }

    private PptTaskView view(PptTask task, PptGenerationContext context) {
        List<PptCheckpointEvent> events = safeEvents(task.id());
        List<PptState> completed = new ArrayList<>();
        for (PptCheckpointEvent event : events) {
            if (PptCheckpointEvent.OUTCOME_SUCCEEDED.equals(event.outcome())
                    && !completed.contains(event.stage())) {
                completed.add(event.stage());
            }
        }
        int progress = task.status() == PptState.SUCCESS ? 100
                : Math.max(0, Math.min(99, (int) Math.round(100.0 * completed.size() / PIPELINE.size())));
        boolean terminal = task.status() == PptState.SUCCESS || task.status() == PptState.CANCELLED;
        PptTaskCapabilities capabilities = new PptTaskCapabilities(
                !terminal && task.status() != PptState.AWAITING_INPUT,
                task.status() != PptState.SUCCESS && task.status() != PptState.CANCELLED
                        && task.status() != PptState.AWAITING_INPUT,
                task.status() == PptState.AWAITING_INPUT,
                task.status() == PptState.SUCCESS
                        && (context != null && context.artifactRef() != null || hasOutputFile(null, task.id())),
                task.status() == PptState.SUCCESS);
        PptFailure failure = null;
        List<PptWarning> warnings = List.of();
        if (context != null) {
            try { failure = generationService.failureOf(task); } catch (RuntimeException ignored) { }
            try { warnings = generationService.warningsOf(task); } catch (RuntimeException ignored) { }
        }
        return new PptTaskView(task.id(), task.conversationId(),
                context == null ? "CREATE" : context.operation(), task.status(), task.runStatus(),
                task.revision(), currentStageLabel(task), completed, progress, progressEvents(events),
                context == null ? null : context.clarifyingQuestion(), failure, warnings,
                context == null ? null : context.artifactRef(), context == null ? null : context.baseTaskId(),
                context == null ? null : context.baseArtifactId(), task.createdAtMillis(),
                task.updatedAtMillis(), capabilities);
    }

    private List<PptTaskView.ProgressEvent> progressEvents(List<PptCheckpointEvent> events) {
        List<PptTaskView.ProgressEvent> result = new ArrayList<>();
        long sequence = 0;
        for (PptCheckpointEvent event : events) {
            sequence++;
            boolean detail = PptCheckpointEvent.OUTCOME_PROGRESS.equals(event.outcome());
            String message = event.outputSummary() == null || event.outputSummary().isBlank()
                    ? defaultMessage(event) : event.outputSummary();
            Integer current = null;
            Integer total = null;
            Matcher counter = COUNTER.matcher(message);
            if (counter.find()) {
                current = Integer.valueOf(counter.group(1));
                total = Integer.valueOf(counter.group(2));
            }
            result.add(new PptTaskView.ProgressEvent(sequence, event.stage(), detail ? "DETAIL" : "STAGE",
                    visibleStatus(event), message, current, total,
                    event.finishedAtMillis() > 0 ? event.finishedAtMillis() : event.startedAtMillis()));
        }
        return List.copyOf(result);
    }

    private static String visibleStatus(PptCheckpointEvent event) {
        if (event.warningCode() != null && !event.warningCode().isBlank()) return "WARNING";
        return switch (event.outcome()) {
            case PptCheckpointEvent.OUTCOME_STARTED -> "STARTED";
            case PptCheckpointEvent.OUTCOME_SUCCEEDED, PptCheckpointEvent.OUTCOME_PROGRESS -> "COMPLETED";
            case PptCheckpointEvent.OUTCOME_FAILED -> "FAILED";
            case PptCheckpointEvent.OUTCOME_CANCELLED -> "CANCELLED";
            default -> "STARTED";
        };
    }

    private static String defaultMessage(PptCheckpointEvent event) {
        String label = STAGE_LABELS.getOrDefault(event.stage(), event.stage().name());
        return switch (event.outcome()) {
            case PptCheckpointEvent.OUTCOME_STARTED -> "正在" + label + "…";
            case PptCheckpointEvent.OUTCOME_SUCCEEDED -> label + "完成";
            case PptCheckpointEvent.OUTCOME_FAILED -> label + "失败";
            case PptCheckpointEvent.OUTCOME_CANCELLED -> "已取消：" + label;
            default -> label;
        };
    }

    private String currentStageLabel(PptTask task) {
        if (task.status() == PptState.AWAITING_INPUT) return "等待补充主题";
        if (task.status() == PptState.SUCCESS) return "PPT 生成完成";
        if (task.status() == PptState.CANCELLED) return "PPT 生成已取消";
        return STAGE_LABELS.getOrDefault(task.status(), task.status().name());
    }

    private PptGenerationContext safeContext(PptTask task) {
        try {
            return generationService.contextOf(task);
        } catch (RuntimeException malformed) {
            log.warn("PPT context unavailable for task view taskId={} revision={}", task.id(), task.revision(), malformed);
            return null;
        }
    }

    private List<PptCheckpointEvent> safeEvents(long taskId) {
        try {
            List<PptCheckpointEvent> events = generationService.eventsOf(taskId);
            return events == null ? List.of() : events;
        } catch (RuntimeException failure) {
            log.warn("PPT progress events unavailable taskId={}", taskId, failure);
            return List.of();
        }
    }

    private boolean hasOutputFile(String userId, long taskId) {
        try {
            String output = userId == null ? generationService.outputPathOf(taskId)
                    : generationService.outputPathOf(userId, taskId);
            return Files.isRegularFile(Path.of(output));
        } catch (InvalidPathException | NullPointerException ignored) {
            return false;
        }
    }

    private static Map<PptState, String> stageLabels() {
        EnumMap<PptState, String> labels = new EnumMap<>(PptState.class);
        labels.put(PptState.INIT, "初始化任务");
        labels.put(PptState.CLARIFY, "采集 PPT 主题");
        labels.put(PptState.REQUIREMENT, "结构化需求");
        labels.put(PptState.SEARCH, "收集相关资料");
        labels.put(PptState.VISUAL_PLAN, "规划视觉风格");
        labels.put(PptState.TEMPLATE, "准备 PPT 模板");
        labels.put(PptState.OUTLINE, "生成 PPT 大纲");
        labels.put(PptState.SCHEMA, "编排页面内容");
        labels.put(PptState.IMAGE, "生成图片素材");
        labels.put(PptState.RENDER, "渲染 PPT");
        labels.put(PptState.VERIFY, "校验 PPT 产物");
        return Map.copyOf(labels);
    }
}
