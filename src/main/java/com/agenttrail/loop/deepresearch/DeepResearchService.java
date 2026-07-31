package com.agenttrail.loop.deepresearch;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.structured.JsonRepair;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * DeepResearch 骨架（issue #25）+ 分层并发调度（issue #34）+ 任务失败重试（issue #36）：
 * 需求澄清 → 研究主题生成 → 按 order 分层的执行计划（同层并发、跨层串行，单个任务失败按配置
 * 上限真正重试）→ 综合成最终报告。
 *
 * <p>不重新实现一套 ReAct 机制——{@link AgentLoopExecutor} 本身就是现成的 ReAct 循环，
 * 这里只是编排"用哪个执行器、按什么顺序调几次 {@code call()}"，每一步都是一次独立、
 * 无状态的同步调用（各自用新生成的 conversationId，互不共享历史）。
 *
 * <p>{@code searchExecutor} 必须是挂了联网搜索工具的执行器（{@code AgentLoopExecutorFactory
 * .forModel(id, true)}），{@code plainExecutor} 是同一个模型不挂搜索的版本——需求澄清、主题生成、
 * 计划拆解、最终总结这几步都不需要工具，没必要让模型在这几步也能选择去调搜索工具。
 *
 * <p>不含批判环节（issue #35）、不含专用上下文压缩（issue #37）——这些都是后续票的范围。
 */
public class DeepResearchService {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchService.class);

    /** 没有命中固定标记时的关键词兜底——同样不做语义解析，只是简单的包含匹配。 */
    private static final List<String> INSUFFICIENT_INFO_KEYWORDS = List.of(
            "请提供更多", "能否说明", "需要您提供", "请补充", "不够明确", "请问您");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentLoopExecutor plainExecutor;
    private final AgentLoopExecutor searchExecutor;
    private final int maxConcurrentTasksPerLayer;
    private final int maxTasksPerPlan;
    private final int maxTaskRetries;

    public DeepResearchService(AgentLoopExecutor plainExecutor, AgentLoopExecutor searchExecutor) {
        this(plainExecutor, searchExecutor, 3, 20, 2);
    }

    /**
     * @param maxConcurrentTasksPerLayer 同一层内最多同时跑几个任务子循环（issue #34 要求的 Semaphore(3)）
     * @param maxTasksPerPlan            单次计划最多允许的任务总数（issue #34 明确指出参考实现没有的广度上限）；
     *                                   超出时按模型给出的原始顺序截断到这个数——顺序本身已经编码了"越靠前
     *                                   越基础/越先被依赖"（order 从小到大），截断保留前面的任务比随机丢弃
     *                                   或整体拒绝更合理；也不做"打回去让模型重新规划"，那样在模型持续
     *                                   给出超量计划时会变成无限重试，"硬上限"这个词本身就意味着确定性截断，
     *                                   不是一个还需要再谈判的软约束
     */
    public DeepResearchService(AgentLoopExecutor plainExecutor, AgentLoopExecutor searchExecutor,
            int maxConcurrentTasksPerLayer, int maxTasksPerPlan) {
        this(plainExecutor, searchExecutor, maxConcurrentTasksPerLayer, maxTasksPerPlan, 2);
    }

    /**
     * @param maxTaskRetries 单个任务失败后最多重试几次（issue #36）——参考实现声明了这个参数
     *                       但从未真正生效，只尝试一次就返回失败；这里是真的按这个次数重试，
     *                       耗尽后记一条失败结果，不抛异常、不阻塞其余任务/其余层继续跑
     */
    public DeepResearchService(AgentLoopExecutor plainExecutor, AgentLoopExecutor searchExecutor,
            int maxConcurrentTasksPerLayer, int maxTasksPerPlan, int maxTaskRetries) {
        this.plainExecutor = plainExecutor;
        this.searchExecutor = searchExecutor;
        this.maxConcurrentTasksPerLayer = maxConcurrentTasksPerLayer;
        this.maxTasksPerPlan = maxTasksPerPlan;
        this.maxTaskRetries = maxTaskRetries;
    }

    public DeepResearchReport research(String question) {
        String clarification = plainExecutor.call(DeepResearchPrompts.CLARIFICATION + question, freshParams(null));
        if (needsMoreInfo(clarification)) {
            return DeepResearchReport.needsClarification(stripMarkers(clarification));
        }

        String topic = plainExecutor.call(DeepResearchPrompts.TOPIC_GENERATION + question, freshParams(null));
        log.info("DeepResearch 研究主题：{}", topic);

        ResearchPlan plan = applyBreadthCap(generatePlan(topic));
        log.info("DeepResearch 执行计划：{} 个任务", plan.tasks().size());

        List<TaskResult> results = executeLayered(plan);

        String report = summarize(question, topic, results);
        return DeepResearchReport.completed(topic, results, report);
    }

    /** 固定标记优先；两个标记都没出现时才走关键词兜底，都不命中默认视为信息充分（参考实现的取舍：宁可少追问）。 */
    private static boolean needsMoreInfo(String modelResponse) {
        if (modelResponse.contains(DeepResearchPrompts.NEEDS_INFO_MARKER)) {
            return true;
        }
        if (modelResponse.contains(DeepResearchPrompts.READY_MARKER)) {
            return false;
        }
        return INSUFFICIENT_INFO_KEYWORDS.stream().anyMatch(modelResponse::contains);
    }

    private static String stripMarkers(String modelResponse) {
        return modelResponse.replace(DeepResearchPrompts.NEEDS_INFO_MARKER, "").trim();
    }

    private ResearchPlan generatePlan(String topic) {
        RunnableParams params = freshParams(OutputType.of(ResearchPlan.class));
        String rawJson = plainExecutor.call(DeepResearchPrompts.PLAN + topic, params);
        String fixed = JsonRepair.fixJson(rawJson);
        try {
            return MAPPER.readValue(fixed, ResearchPlan.class);
        } catch (Exception malformed) {
            // JsonRepair 修不动时的最后兜底是把原文整个包成 {"content": "..."}（见其类注释）——
            // 真实模型输出偶尔会因为夹带解释性文字这类小瑕疵落到这条兜底路径，但被包起来的
            // "content" 字符串本身往往仍是一份完整合法的 ResearchPlan JSON，只是外面多包了一层。
            // 先试着拆开这一层再解析一次，比直接判定"整个计划解析失败"更不容易被无谓地拒绝。
            ResearchPlan unwrapped = tryUnwrapPlainContentFallback(fixed);
            if (unwrapped != null) {
                return unwrapped;
            }
            throw new IllegalStateException("执行计划解析失败，模型输出不是合法的 ResearchPlan JSON：" + rawJson, malformed);
        }
    }

    private static ResearchPlan tryUnwrapPlainContentFallback(String fixedJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(fixedJson);
            com.fasterxml.jackson.databind.JsonNode content = root.get("content");
            if (content == null || !content.isTextual()) {
                return null;
            }
            return MAPPER.readValue(content.asText(), ResearchPlan.class);
        } catch (Exception stillNotParseable) {
            return null;
        }
    }

    private ResearchPlan applyBreadthCap(ResearchPlan plan) {
        if (plan.tasks().size() <= maxTasksPerPlan) {
            return plan;
        }
        log.warn("DeepResearch 计划任务数 {} 超过上限 {}，按原始顺序截断到前 {} 个",
                plan.tasks().size(), maxTasksPerPlan, maxTasksPerPlan);
        return new ResearchPlan(plan.tasks().subList(0, maxTasksPerPlan));
    }

    /**
     * 按 order 分层执行：同层内并发（{@link #maxConcurrentTasksPerLayer} 限流），跨层严格串行——
     * 前一层全部跑完才进入下一层。N 层任务的依赖上下文只包含 N-1 层的结果（单跳依赖，issue #34
     * 明确的简化设计：多跳依赖要感知"任务 A 依赖任务 B 而不是整个上一层"，需要模型在计划阶段
     * 显式声明依赖边，这一票不做）。
     */
    private List<TaskResult> executeLayered(ResearchPlan plan) {
        Map<Integer, List<ResearchTask>> byOrder = plan.tasks().stream()
                .collect(Collectors.groupingBy(ResearchTask::order, TreeMap::new, Collectors.toList()));

        List<TaskResult> allResults = new ArrayList<>();
        String previousLayerContext = "无";

        for (Map.Entry<Integer, List<ResearchTask>> layer : byOrder.entrySet()) {
            log.info("DeepResearch 第 {} 层：{} 个任务并发执行", layer.getKey(), layer.getValue().size());
            List<TaskResult> layerResults = executeLayerConcurrently(layer.getValue(), previousLayerContext);
            allResults.addAll(layerResults);
            previousLayerContext = renderLayerContext(layerResults);
        }
        return allResults;
    }

    /** 虚拟线程按任务数量各起一个，真正的并发上限由 {@link Semaphore} 控制，不依赖线程池大小。 */
    private List<TaskResult> executeLayerConcurrently(List<ResearchTask> tasks, String dependencyContext) {
        Semaphore concurrencyGate = new Semaphore(maxConcurrentTasksPerLayer);
        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TaskResult>> futures = new ArrayList<>();
            for (ResearchTask task : tasks) {
                futures.add(virtualThreads.submit(() -> {
                    concurrencyGate.acquire();
                    try {
                        return executeTask(task, dependencyContext);
                    } finally {
                        concurrencyGate.release();
                    }
                }));
            }
            List<TaskResult> results = new ArrayList<>();
            for (Future<TaskResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("本层任务执行被中断", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("本层任务执行失败：" + failure.getCause().getMessage(), failure.getCause());
        }
    }

    /**
     * 最多尝试 {@code maxTaskRetries + 1} 次（1 次初始 + N 次重试）；每次失败都记日志，
     * 全部尝试都失败时返回一个失败态的 {@link TaskResult} 而不是往外抛异常——调用方
     * （{@link #executeLayerConcurrently}）不需要为"某一个任务失败了"特殊处理，
     * 这一层内其余任务、以及后续层，都不受一个任务耗尽重试的影响。
     */
    private TaskResult executeTask(ResearchTask task, String dependencyContext) {
        String instructionWithContext = task.order() == 1
                ? task.instruction()
                : "【上一层已知信息】\n" + dependencyContext + "\n\n【当前任务】\n" + task.instruction();

        String lastErrorMessage = null;
        for (int attempt = 1; attempt <= maxTaskRetries + 1; attempt++) {
            log.info("DeepResearch 执行任务 {}（第 {} 层，第 {} 次尝试）：{}",
                    task.id(), task.order(), attempt, task.instruction());
            try {
                String output = searchExecutor.call(
                        DeepResearchPrompts.EXECUTE + instructionWithContext, freshParams(null));
                return TaskResult.success(task.id(), task.instruction(), output);
            } catch (Exception failure) {
                lastErrorMessage = failure.getMessage();
                log.warn("DeepResearch 任务 {} 第 {} 次尝试失败：{}", task.id(), attempt, lastErrorMessage);
            }
        }
        log.error("DeepResearch 任务 {} 连续 {} 次尝试均失败，放弃重试", task.id(), maxTaskRetries + 1);
        return TaskResult.failure(task.id(), task.instruction(), lastErrorMessage);
    }

    private static String renderLayerContext(List<TaskResult> layerResults) {
        StringBuilder context = new StringBuilder();
        for (TaskResult result : layerResults) {
            context.append("- ").append(result.instruction()).append("：\n")
                    .append(renderResultOrFailure(result)).append("\n\n");
        }
        return context.isEmpty() ? "无" : context.toString();
    }

    private String summarize(String question, String topic, List<TaskResult> results) {
        StringBuilder context = new StringBuilder(DeepResearchPrompts.SUMMARIZE);
        context.append("【用户原始问题】\n").append(question)
                .append("\n\n【研究主题】\n").append(topic)
                .append("\n\n【检索结果】\n");
        if (results.isEmpty()) {
            context.append("（未检索到相关结果）");
        } else {
            for (TaskResult result : results) {
                context.append("- ").append(result.instruction()).append("：\n")
                        .append(renderResultOrFailure(result)).append("\n\n");
            }
        }
        return plainExecutor.call(context.toString(), freshParams(null));
    }

    private static String renderResultOrFailure(TaskResult result) {
        return result.success() ? result.output() : "（该任务重试后仍失败，无可用结果：" + result.errorMessage() + "）";
    }

    private static RunnableParams freshParams(OutputType outputType) {
        return new RunnableParams(UUID.randomUUID().toString(), "deepresearch", Map.of(), outputType);
    }
}
