package com.agenttrail.loop.task;

import com.agenttrail.loop.model.AgentStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级任务管理：单飞（同一会话同时只能跑一个任务）+ 停止。
 *
 * <p>停止必须同时做两件事，缺一不可：
 * <ul>
 *   <li><b>取消上游订阅</b>——真正让模型停止生成。只断开前端连接的话，模型还在继续吐 token、
 *       继续计费，只是没人看而已
 *   <li><b>关闭下游事件流</b>——否则前端一直挂着等一个永远不会到来的结束信号
 * </ul>
 *
 * <p>本版是纯内存单实例实现。多实例部署下"本地 map 里找不到任务"不等于"任务不存在"，
 * 需要 Redis 广播兜底，见 ticket #12。
 *
 * <p>目前只有 {@code stopTask} 这一种"硬停止"语义（丢弃全部运行时状态）。
 * "先快照再停、之后可恢复"的中断语义属于断点续传，见 ticket #13。
 */
public class AgentTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskManager.class);

    private final Map<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    /** 一个在跑的任务：事件流出口 + 当前轮次的上游订阅。 */
    private static final class TaskInfo {

        private final Sinks.Many<AgentStreamEvent> sink;

        /** 每轮都会被换成新的订阅，所以是 volatile：写在循环线程，读在停止请求线程。 */
        private volatile Disposable disposable;

        private TaskInfo(Sinks.Many<AgentStreamEvent> sink) {
            this.sink = sink;
        }
    }

    /**
     * 单飞注册：同一会话已有任务在跑时直接拒绝。
     *
     * <p>用 {@code putIfAbsent} 一步完成"不存在则写入"（踩坑点 #10）。
     * 写成"先 containsKey 再 put"的话，两个并发请求可能同时查到"没有任务"然后都写入成功，
     * 同一会话就跑起了两个任务，输出交错。
     *
     * @return true 表示注册成功；false 表示该会话已有任务在跑
     */
    public boolean registerTask(String conversationId, Sinks.Many<AgentStreamEvent> sink) {
        boolean admitted = taskMap.putIfAbsent(conversationId, new TaskInfo(sink)) == null;
        if (!admitted) {
            log.warn("会话 {} 已有任务在执行，拒绝并发注册", conversationId);
        }
        return admitted;
    }

    /**
     * 登记当前轮次的上游订阅——**每一轮都要调用**（踩坑点 #9）。
     *
     * <p>循环每轮都会发起新的模型调用、产生新的订阅。如果只登记第一轮的，
     * 停止请求会作用在一个早就结束的订阅上，当前真正在跑的那一轮根本停不下来。
     *
     * <p>任务已经不在了（比如客户端断连后任务已被移除）说明这是个孤儿订阅，
     * 直接释放掉，不然就泄漏了。
     */
    public void setDisposable(String conversationId, Disposable subscription) {
        TaskInfo task = taskMap.get(conversationId);
        if (task == null) {
            disposeQuietly(subscription);
            log.debug("会话 {} 的任务已结束，释放孤儿订阅", conversationId);
            return;
        }
        task.disposable = subscription;
    }

    public boolean hasRunningTask(String conversationId) {
        return taskMap.containsKey(conversationId);
    }

    /**
     * 硬停止：取消上游订阅 + 关闭下游事件流，丢弃全部运行时状态。
     *
     * <p>用 {@code remove} 的原子返回值判断任务是否存在，而不是"先 get 判断、再 remove"——
     * 后者在两步之间如果有新任务注册进来，会被误删（踩坑点 #10）。
     *
     * @return true 表示确实停掉了一个在跑的任务
     */
    public boolean stopTask(String conversationId) {
        TaskInfo task = taskMap.remove(conversationId);
        if (task == null) {
            log.warn("会话 {} 没有在跑的任务可停止", conversationId);
            return false;
        }
        disposeQuietly(task.disposable);
        task.sink.tryEmitComplete();
        log.debug("已停止会话 {} 的任务", conversationId);
        return true;
    }

    /** 任务正常跑完后的清理，不碰订阅和事件流——那两者此时已经自然结束了。 */
    public void removeTask(String conversationId) {
        taskMap.remove(conversationId);
    }

    /** dispose 本身的异常不应该影响停止流程——停止是尽力而为的操作。 */
    private static void disposeQuietly(Disposable disposable) {
        if (disposable == null || disposable.isDisposed()) {
            return;
        }
        try {
            disposable.dispose();
        } catch (Exception ignored) {
            log.debug("忽略取消订阅时的异常: {}", ignored.getMessage());
        }
    }
}
