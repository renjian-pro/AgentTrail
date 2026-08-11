package com.agenttrail.capability.deepresearch;

import com.agenttrail.loop.context.ContextCompactor;
import com.agenttrail.loop.context.MessageRendering;
import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.StructuredLlmCall;
import com.agenttrail.loop.model.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * DeepResearch 骨架（issue #25）+ 分层并发调度（issue #34）+ 任务失败重试（issue #36）+
 * 自我批判循环（issue #35）+ 专用上下文压缩（issue #37）：需求澄清 → 研究主题生成 →
 * 【计划（按 order 分层）→ 执行 → 结构化批判】循环，不通过且未到轮次上限时把批判反馈拼进
 * 下一轮计划生成，通过或到达轮次上限则停止 → 综合成最终报告。
 *
 * <p>不重新实现一套 ReAct 机制——{@link AgentLoopExecutor} 本身就是现成的 ReAct 循环，
 * 这里只是编排"用哪个执行器、按什么顺序调几次 {@code call()}"，每一步都是一次独立、
 * 无状态的同步调用（各自用新生成的 conversationId，互不共享历史）。
 *
 * <p>{@code searchExecutor} 必须是挂了联网搜索工具的执行器（{@code AgentLoopExecutorFactory
 * .forModel(id, true)}），{@code plainExecutor} 是同一个模型不挂搜索的版本——需求澄清、主题生成、
 * 计划拆解、批判判定、最终总结这几步都不需要工具，没必要让模型在这几步也能选择去调搜索工具。
 *
 * <p>批判循环不依赖 issue #34 的并发分层——{@link #executeLayered} 本身就同时兼容"单层顺序"
 * 和"多层并发"两种计划形状，批判只是在它外面再套一层"要不要再来一轮"的判断，两票可以独立演进。
 *
 * <p>专用上下文压缩（issue #37）压的不是一份 {@code List<Message>} 会话历史——每一步 LLM 调用
 * 都是独立同步请求，压根没有那样的历史（见上面每一步"新生成的 conversationId"的说明）。真正会
 * 无界增长的是这个类自己手工拼的文本："已完成任务的检索结果"这一段会随着批判轮次增多而累积。
 * 这里把它也建模成一份 {@code List<Message>}（每个 {@link TaskResult} 包一层
 * {@link ToolResponseMessage}，让 {@link ContextCompactor} 已有的两层机制——旧的/超长的
 * 检索结果被压成占位符、整体超预算时被摘要——原样复用，不另起一套"字符数阈值 + 一次性摘要替换"
 * 的简化机制。批判反馈额外打上 {@link #CRITIQUE_FEEDBACK_MARKER} 标记，交给
 * {@link ContextCompactor} 新增的"按标记只保留最新一条"规则处理——历史批判意见一旦被更新的
 * 一条取代就没有继续参考的价值，这条规则真删除而不是压缩，且不受 token 阈值门槛限制。
 */
public class DeepResearchService {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchService.class);

    /** 没有命中固定标记时的关键词兜底——同样不做语义解析，只是简单的包含匹配。 */
    private static final List<String> INSUFFICIENT_INFO_KEYWORDS = List.of(
            "请提供更多", "能否说明", "需要您提供", "请补充", "不够明确", "请问您");

    /**
     * 批判反馈消息的标记前缀（issue #37）——配上 {@link com.agenttrail.loop.context.ContextPolicy
     * #retainLatestOnlyMarkers()} 使用，让 {@link ContextCompactor} 只在累积的检索结果上下文里
     * 保留最新一条批判反馈，更早几轮的批判意见在渲染/压缩时被过滤掉，不参与累积。公开是因为生产
     * 装配（{@code DeepResearchConfig}）需要用同一个字面量去配置 {@code ContextPolicy}。
     */
    public static final String CRITIQUE_FEEDBACK_MARKER = "[CRITIQUE_FEEDBACK]";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentLoopExecutor plainExecutor;
    private final AgentLoopExecutor searchExecutor;
    private final int maxConcurrentTasksPerLayer;
    private final int maxTasksPerPlan;
    private final int maxTaskRetries;
    private final int maxCritiqueRounds;
    private final ContextCompactor researchContextCompactor;
    private final ConcurrencyPolicy concurrencyPolicy;

    public DeepResearchService(AgentLoopExecutor plainExecutor, AgentLoopExecutor searchExecutor) {
        this(plainExecutor, searchExecutor, 3, 20, 2, 3);
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
        this(plainExecutor, searchExecutor, maxConcurrentTasksPerLayer, maxTasksPerPlan, 2, 3);
    }

    /**
     * @param maxTaskRetries 单个任务失败后最多重试几次（issue #36）——参考实现声明了这个参数
     *                       但从未真正生效，只尝试一次就返回失败；这里是真的按这个次数重试，
     *                       耗尽后记一条失败结果，不抛异常、不阻塞其余任务/其余层继续跑
     */
    public DeepResearchService(AgentLoopExecutor plainExecutor, AgentLoopExecutor searchExecutor,
            int maxConcurrentTasksPerLayer, int maxTasksPerPlan, int maxTaskRetries) {
        this(plainExecutor, searchExecutor, maxConcurrentTasksPerLayer, maxTasksPerPlan, maxTaskRetries, 3);
    }

    /**
     * @param maxCritiqueRounds 批判循环最多跑几轮（issue #35）——第一轮计划-执行总会跑，之后每次
     *                          批判不通过就再来一轮，直到批判通过或者跑满这个轮数为止；到达轮数
     *                          上限时不管批判有没有通过都直接进入总结，不无限重试
     */
    public DeepResearchService(AgentLoopExecutor plainExecutor, AgentLoopExecutor searchExecutor,
            int maxConcurrentTasksPerLayer, int maxTasksPerPlan, int maxTaskRetries, int maxCritiqueRounds) {
        this(plainExecutor, searchExecutor, maxConcurrentTasksPerLayer, maxTasksPerPlan, maxTaskRetries,
                maxCritiqueRounds, null);
    }

    /**
     * @param researchContextCompactor DeepResearch 专用的上下文压缩器（issue #37）——压缩的是
     *                                 {@link #critique}/{@link #summarize} 用到的累积检索结果 +
     *                                 批判反馈这份文本，跟 {@code AgentLoopExecutor} 自己内部
     *                                 ReAct 子循环的上下文压缩是两个独立的东西，互不干扰；传 null
     *                                 表示不压缩，行为与没有这个机制时一致
     */
    public DeepResearchService(AgentLoopExecutor plainExecutor, AgentLoopExecutor searchExecutor,
            int maxConcurrentTasksPerLayer, int maxTasksPerPlan, int maxTaskRetries, int maxCritiqueRounds,
            ContextCompactor researchContextCompactor) {
        this(plainExecutor, searchExecutor, maxConcurrentTasksPerLayer, maxTasksPerPlan, maxTaskRetries,
                maxCritiqueRounds, researchContextCompactor, new SemaphoreConcurrencyPolicy(maxConcurrentTasksPerLayer));
    }

    public DeepResearchService(AgentLoopExecutor plainExecutor, AgentLoopExecutor searchExecutor,
            int maxConcurrentTasksPerLayer, int maxTasksPerPlan, int maxTaskRetries, int maxCritiqueRounds,
            ContextCompactor researchContextCompactor, ConcurrencyPolicy concurrencyPolicy) {
        this.plainExecutor = plainExecutor;
        this.searchExecutor = searchExecutor;
        this.maxConcurrentTasksPerLayer = maxConcurrentTasksPerLayer;
        this.maxTasksPerPlan = maxTasksPerPlan;
        this.maxTaskRetries = maxTaskRetries;
        this.maxCritiqueRounds = maxCritiqueRounds;
        this.researchContextCompactor = researchContextCompactor;
        this.concurrencyPolicy = concurrencyPolicy;
    }

    public DeepResearchReport research(String question) {
        return research(question, null);
    }

    public DeepResearchReport research(String question, Consumer<String> onStepChange) {
        notifyStep(onStepChange, "CLARIFYING");
        String clarification = plainExecutor.call(DeepResearchPrompts.CLARIFICATION + question, freshParams(null));
        if (needsMoreInfo(clarification)) {
            return DeepResearchReport.needsClarification(stripMarkers(clarification));
        }
        return proceedFromTopic(question, onStepChange);
    }

    /**
     * 需求澄清后的续接入口：只追问一轮，用户回复后不管信息是否依然不够都直接开始研究，
     * 不再反复打断——{@code previousQuestion}/{@code previousClarifyingQuestion} 拼回用户这轮的
     * 回复，合成一份完整问题直接跳过 {@link #needsMoreInfo} 判断（该判断只在第一次调用
     * {@link #research(String)} 时跑一次）。
     */
    public DeepResearchReport continueAfterClarification(String previousQuestion, String previousClarifyingQuestion,
            String userReply) {
        return continueAfterClarification(previousQuestion, previousClarifyingQuestion, userReply, null);
    }

    public DeepResearchReport continueAfterClarification(String previousQuestion, String previousClarifyingQuestion,
            String userReply, Consumer<String> onStepChange) {
        notifyStep(onStepChange, "CLARIFYING");
        String combinedQuestion = "【此前的研究请求】\n" + previousQuestion
                + "\n\n【助手追问】\n" + previousClarifyingQuestion
                + "\n\n【用户补充】\n" + userReply;
        return proceedFromTopic(combinedQuestion, onStepChange);
    }

    private DeepResearchReport proceedFromTopic(String question, Consumer<String> onStepChange) {
        notifyStep(onStepChange, "PLANNING");
        String topic = plainExecutor.call(DeepResearchPrompts.TOPIC_GENERATION + question, freshParams(null));
        log.info("DeepResearch 研究主题：{}", topic);

        ResearchLoopOutcome outcome = planExecuteCritiqueLoop(topic, onStepChange);

        notifyStep(onStepChange, "SUMMARIZING");
        String report = summarize(question, topic, outcome.researchContext());
        return DeepResearchReport.completed(topic, outcome.allResults(), report);
    }

    /**
     * 计划 → 执行 → 批判的循环：批判反馈拼进下一轮的计划生成，让模型针对性补充而不是盲目重跑
     * 一遍一样的任务；每一轮新产出的任务结果都累加进最终结果集（不丢弃前面几轮已经查到的内容）。
     *
     * @return {@code allResults} 是返回给调用方的原始结构化结果（issue #37 的压缩不影响它——
     *         压缩只作用于喂给 LLM 的文本，不该影响 API 返回给外部消费者的数据完整性）；
     *         {@code researchContext} 是喂给 {@link #critique}/{@link #summarize} 的累积上下文，
     *         每一轮结束都可能被 {@link #researchContextCompactor} 原地压缩过
     */
    private ResearchLoopOutcome planExecuteCritiqueLoop(String topic, Consumer<String> onStepChange) {
        List<TaskResult> allResults = new ArrayList<>();
        List<Message> researchContext = new ArrayList<>();
        String previousFeedback = null;

        for (int round = 1; round <= maxCritiqueRounds; round++) {
            if (round > 1) {
                notifyStep(onStepChange, "PLANNING");
            }
            String planInput = previousFeedback == null
                    ? topic
                    : topic + "\n\n【上一轮批判反馈，本轮需针对性补充】\n" + previousFeedback;
            ResearchPlan plan = applyBreadthCap(generatePlan(planInput));
            log.info("DeepResearch 第 {} 轮执行计划：{} 个任务", round, plan.tasks().size());

            notifyStep(onStepChange, "SEARCHING");
            List<TaskResult> roundResults = executeLayered(plan);
            allResults.addAll(roundResults);
            roundResults.forEach(result -> researchContext.add(taskResultToContextMessage(result)));

            if (round == maxCritiqueRounds) {
                log.info("DeepResearch 批判循环已达轮数上限 {}，直接进入总结", maxCritiqueRounds);
                break;
            }

            notifyStep(onStepChange, "CRITIQUING");
            CritiqueResult critique = critique(topic, researchContext);
            log.info("DeepResearch 第 {} 轮批判：{}{}", round, critique.passed() ? "通过" : "不通过",
                    critique.passed() ? "" : "，反馈：" + critique.feedback());
            if (critique.passed()) {
                break;
            }
            previousFeedback = critique.feedback();
            researchContext.add(critiqueFeedbackContextMessage(previousFeedback));
        }
        return new ResearchLoopOutcome(allResults, researchContext);
    }

    /** {@link #planExecuteCritiqueLoop} 的两份输出——见该方法的类注释，两者服务于不同消费方。 */
    private record ResearchLoopOutcome(List<TaskResult> allResults, List<Message> researchContext) {
    }

    /** 把一个任务结果包成 {@link ToolResponseMessage}——借用"工具结果"这个消息形状，
     * 让 {@link ContextCompactor} 已有的 micro_compact（旧的/超长的工具内容换占位符）
     * 原样对研究结果生效，不需要为 DeepResearch 的检索结果另写一套压缩判断。 */
    private static Message taskResultToContextMessage(TaskResult result) {
        String responseData = result.instruction() + "：\n" + renderResultOrFailure(result);
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(result.taskId(), result.taskId(), responseData)))
                .build();
    }

    /** 打上 {@link #CRITIQUE_FEEDBACK_MARKER} 标记，交给 {@link ContextCompactor} 的
     * "按标记只保留最新一条"规则处理——见类注释。 */
    private static Message critiqueFeedbackContextMessage(String feedback) {
        return new UserMessage(CRITIQUE_FEEDBACK_MARKER + "\n" + feedback);
    }

    /**
     * 把累积的研究上下文（可能被压缩过）渲染成文本，喂进 {@link #critique}/{@link #summarize}
     * 的提示词。压缩发生在渲染之前——{@link ContextCompactor} 原地修改传入的列表，之后同一份
     * 列表在下一次调用这个方法时会看到上一次已经压过的结果，压缩效果因此能跨轮次持续生效，
     * 不是"压完这一次就没了"。
     */
    private String renderResearchContext(List<Message> researchContext, String topic) {
        if (researchContext.isEmpty()) {
            return "（未检索到任何结果）";
        }
        if (researchContextCompactor != null) {
            researchContextCompactor.compact(researchContext, topic);
        }
        return MessageRendering.render(researchContext);
    }

    /** 结构化布尔判定（issue #18 机制复用），不做自由文本解析猜测。 */
    private CritiqueResult critique(String topic, List<Message> researchContext) {
        StringBuilder input = new StringBuilder(DeepResearchPrompts.CRITIQUE);
        input.append(topic).append("\n\n已完成任务的检索结果：\n")
                .append(renderResearchContext(researchContext, topic));

        RunnableParams params = freshParams(OutputType.of(CritiqueResult.class));
        try {
            return StructuredLlmCall.call(plainExecutor, input.toString(), params, CritiqueResult.class);
        } catch (StructuredLlmCall.StructuredLlmCallException malformed) {
            // 和 generatePlan() 同样的兜底手法：JsonRepair 修不动时可能落到 {"content": "..."} 信封，
            // 里面往往还是一份合法的 CritiqueResult JSON。这里解不开就不再深究——批判判定失败时
            // 保守地当作"不通过"处理，让循环继续跑而不是让一次解析失败直接中断整个研究流程。
            CritiqueResult unwrapped = tryUnwrapCritiqueContentFallback(malformed.fixedJson());
            if (unwrapped != null) {
                return unwrapped;
            }
            log.warn("DeepResearch 批判结果解析失败，保守按不通过处理：{}", malformed.getMessage());
            return new CritiqueResult(false, "批判结果解析失败，本轮判定为不通过以确保继续迭代");
        }
    }

    private static CritiqueResult tryUnwrapCritiqueContentFallback(String fixedJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(fixedJson);
            com.fasterxml.jackson.databind.JsonNode content = root.get("content");
            if (content == null || !content.isTextual()) {
                return null;
            }
            return MAPPER.readValue(content.asText(), CritiqueResult.class);
        } catch (Exception stillNotParseable) {
            return null;
        }
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
        try {
            return StructuredLlmCall.parse(rawJson, ResearchPlan.class);
        } catch (StructuredLlmCall.StructuredLlmCallException malformed) {
            // JsonRepair 修不动时的最后兜底是把原文整个包成 {"content": "..."}（见其类注释）——
            // 真实模型输出偶尔会因为夹带解释性文字这类小瑕疵落到这条兜底路径，但被包起来的
            // "content" 字符串本身往往仍是一份完整合法的 ResearchPlan JSON，只是外面多包了一层。
            // 先试着拆开这一层再解析一次，比直接判定"整个计划解析失败"更不容易被无谓地拒绝。
            ResearchPlan unwrapped = tryUnwrapPlainContentFallback(malformed.fixedJson());
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
        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TaskResult>> futures = new ArrayList<>();
            for (ResearchTask task : tasks) {
                futures.add(virtualThreads.submit(() -> {
                    try (ConcurrencyPolicy.Permit ignored = concurrencyPolicy.acquire("default", "deepresearch.search")) {
                        return executeTask(task, dependencyContext);
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
                return TaskResult.success(task.id(), task.instruction(), task.order(), output);
            } catch (Exception failure) {
                lastErrorMessage = failure.getMessage();
                log.warn("DeepResearch 任务 {} 第 {} 次尝试失败：{}", task.id(), attempt, lastErrorMessage);
            }
        }
        log.error("DeepResearch 任务 {} 连续 {} 次尝试均失败，放弃重试", task.id(), maxTaskRetries + 1);
        return TaskResult.failure(task.id(), task.instruction(), task.order(), lastErrorMessage);
    }

    private static String renderLayerContext(List<TaskResult> layerResults) {
        StringBuilder context = new StringBuilder();
        for (TaskResult result : layerResults) {
            context.append("- ").append(result.instruction()).append("：\n")
                    .append(renderResultOrFailure(result)).append("\n\n");
        }
        return context.isEmpty() ? "无" : context.toString();
    }

    private String summarize(String question, String topic, List<Message> researchContext) {
        StringBuilder context = new StringBuilder(DeepResearchPrompts.SUMMARIZE);
        context.append("【用户原始问题】\n").append(question)
                .append("\n\n【研究主题】\n").append(topic)
                .append("\n\n【检索结果】\n").append(renderResearchContext(researchContext, topic));
        return plainExecutor.call(context.toString(), freshParams(null));
    }

    private static String renderResultOrFailure(TaskResult result) {
        return result.success() ? result.output() : "（该任务重试后仍失败，无可用结果：" + result.errorMessage() + "）";
    }

    private static RunnableParams freshParams(OutputType outputType) {
        return new RunnableParams(UUID.randomUUID().toString(), "deepresearch", Map.of(), outputType);
    }

    private static void notifyStep(Consumer<String> onStepChange, String step) {
        if (onStepChange != null) {
            onStepChange.accept(step);
        }
    }
}
