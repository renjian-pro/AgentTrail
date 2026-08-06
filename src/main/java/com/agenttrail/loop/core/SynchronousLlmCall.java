package com.agenttrail.loop.core;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 同步小模型调用的唯一出口——分类/检索兜底/摘要/提取这类"问一句、等一个完整回复"的调用都应该走
 * 这里，不要各自直接 {@code chatModel.call(prompt)}。
 *
 * <p>理由见踩坑点 #92：{@link LlmInvoker} 已经证明"模型调用必须有超时"这件事，两段式超时
 * （TTFT + 逐 chunk idle）连取消语义都有 {@code LlmInvokerTest} 锁住——但那套机制只包住了主对话的
 * 流式路径。分类器、工具检索兜底、上下文摘要、图片描述、记忆提取这几处调用各自直接同步调
 * {@code chatModel.call()}，各自写了一份"失败按 XX 处理"的 try/catch。catch 接得住*抛异常*的失败，
 * 接不住*一直不返回*的失败——没有超时的网络调用永远不会变成异常，2026-08-06 一次真实的 DashScope
 * 网络卡顿（一条连接卡在读取上 236 秒仍未返回）把这几处全部命中过一遍：全站共享同一个到
 * DashScope 的 OkHttp 连接池（每 host 默认仅 5 个并发位），几个这样的卡死请求就能让全站所有用户
 * 的对话一起卡住，且没有任何错误或降级信号。
 *
 * <p>{@code subscribeOn(Schedulers.boundedElastic())} 是必须的：{@code chatModel.call()} 是阻塞调用，
 * 直接在调用方线程（可能是 Reactor 的非阻塞 I/O 线程）上跑会被 Reactor 的非阻塞线程检查拒绝——
 * 和 {@link AgentLoopExecutor} 里 {@code ToolCallExecutor} 切到 boundedElastic 的理由一致。
 *
 * <p>超时只保证"调用方不会被这次请求拖住太久"，不保证底层 HTTP 连接立刻断开。{@code Mono.timeout()}
 * 触发时 Reactor 确实会尝试给执行阻塞调用的 boundedElastic 线程发一次中断（{@code
 * Future.cancel(true)}，{@code SynchronousLlmCallTest} 用 {@code Thread.sleep()} 验证过这一步真的发生
 * 了）——但阻塞调用本身响不响应中断，取决于它内部用的是不是可中断的阻塞原语：OkHttp 的阻塞 socket
 * 读取通常不是，所以生产上那种"连接没断、就是不出数据"的卡顿，线程大概率还是会一直占着，直到底层
 * 调用自己返回。这是 Reactor 层面能做到的极限：它让业务逻辑（进而是当前这轮对话）立刻拿回控制权，
 * 不再被拖住，但不保证回收底层 HTTP 客户端自己持有的连接池位——那是另一层防御（HTTP 客户端自身的
 * 连接超时），不是这个类的职责。
 */
public final class SynchronousLlmCall {

    /** 分类/检索/摘要/提取这类小调用的默认上限——和 {@link LlmInvoker} 的 idle 超时取值一致，
     *  这类调用不该比"已经开始吐字之后的正常间隔"更慢。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private SynchronousLlmCall() { }

    public static ChatResponse call(ChatModel chatModel, Prompt prompt) {
        return call(chatModel, prompt, DEFAULT_TIMEOUT);
    }

    public static ChatResponse call(ChatModel chatModel, Prompt prompt, Duration timeout) {
        return Mono.fromCallable(() -> chatModel.call(prompt))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(timeout)
                .block();
    }
}
