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
 * <p>多实例部署下"本地 map 里找不到任务"不等于"任务不存在"——那个会话八成正在别的实例上跑。
 * {@link #stopTask} 先走本地快路径（{@link #stopLocalTask}），本地没命中、又配了
 * {@link InterruptBroadcaster} 时，才通过 Redis Pub/Sub 广播出去，让真正持有这个会话的
 * 那个实例的 {@link #stopLocalTask} 被动触发（见 issue #12）。{@code Disposable}/{@code Sinks.Many}
 * 是进程内对象，永远没法跨实例传递——能广播的只是"请停止 conversationId X"这条消息本身，
 * 真正的取消动作必须由持有那个订阅的实例自己执行。
 *
 * <p><b>单飞注册同样要跨实例生效</b>：只在本地 {@code taskMap} 上 {@code putIfAbsent} 只能防住
 * "同一个实例收到两个并发请求"，防不住"两个不同实例各自认为自己没有这个任务，各自注册成功，
 * 同一个会话在两台机器上同时跑了起来"——这正是 {@link RedisTaskLock}（issue #11）存在的意义。
 * 配了 {@code redisTaskLock} 时，{@link #registerTask} 在本地占位成功之后还要去 Redis 抢真正的
 * 归属；抢不到就把本地占位撤回去，让请求方看到的还是"这个会话已经在别处跑着"，而不是本地
 * 占了位却什么都不做的假成功。
 *
 * <p><b>已知的、故意留到后续的缺口</b>：本类不会定时续期已持有的 Redis 锁。一次注册的 TTL
 * 由调用方在构造 {@link RedisTaskLock} 时给定，必须足够覆盖最坏情况下的会话时长，否则一个
 * 跑得比 TTL 还久的会话，它的 Redis 锁会在还在真实运行的时候到期，被另一个实例抢走——
 * 这时两个实例会真的同时跑同一个会话。定时续期（对标各类参考实现里常见的"每 N 分钟刷新一次
 * 本地所有任务的 TTL"的做法）需要一个额外的调度线程和生命周期管理，留作后续 ticket，
 * 这里不顺手加，以免把这次修复的改动面扩大到没有测试覆盖的角落。
 *
 * <p>目前只有 {@code stopTask} 这一种"硬停止"语义（丢弃全部运行时状态）。
 * "先快照再停、之后可恢复"的中断语义属于断点续传，见 ticket #13。
 */
public class AgentTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskManager.class);

    private final Map<String, TaskInfo> taskMap = new ConcurrentHashMap<>();
    private final RedisTaskLock redisTaskLock;
    private final InterruptBroadcaster broadcaster;

    public AgentTaskManager() {
        this(null, null);
    }

    /**
     * @param broadcaster 跨实例中断广播；传 null 表示单实例部署，{@code stopTask} 只走本地快路径，
     *                    行为与没有这个机制时完全一致
     */
    public AgentTaskManager(InterruptBroadcaster broadcaster) {
        this(null, broadcaster);
    }

    /**
     * @param redisTaskLock 跨实例单飞归属校验；传 null 表示单实例部署，{@code registerTask} 只看
     *                      本地 map，行为与没有这个机制时完全一致
     * @param broadcaster   跨实例中断广播；传 null 表示单实例部署，{@code stopTask} 只走本地快路径
     */
    public AgentTaskManager(RedisTaskLock redisTaskLock, InterruptBroadcaster broadcaster) {
        this.redisTaskLock = redisTaskLock;
        this.broadcaster = broadcaster;
        if (broadcaster != null) {
            // 把"收到广播之后干什么"注册进去，本类不需要知道广播是怎么传递过来的
            broadcaster.onInterruptReceived(this::stopLocalTask);
        }
    }

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
     * <p>配了 {@link RedisTaskLock} 时，本地占位成功之后还要去抢真正的跨实例归属——
     * 本地 map 只能防住"同一个实例的并发请求"，防不住"另一个实例已经在跑这个会话，
     * 但本实例的本地 map 里当然看不到"。抢不到就把本地占位撤回去：占着本地位置又什么都不做，
     * 会让 {@link #hasRunningTask} 在本实例上错误地显示"有任务在跑"。
     *
     * @return true 表示注册成功；false 表示该会话已有任务在跑（本实例或者别的实例）
     */
    public boolean registerTask(String conversationId, Sinks.Many<AgentStreamEvent> sink) {
        boolean admittedLocally = taskMap.putIfAbsent(conversationId, new TaskInfo(sink)) == null;
        if (!admittedLocally) {
            log.warn("会话 {} 已有任务在执行，拒绝并发注册", conversationId);
            return false;
        }
        if (redisTaskLock != null && !redisTaskLock.tryAcquire(conversationId)) {
            taskMap.remove(conversationId);
            log.warn("会话 {} 已在别的实例上执行，撤回本地占位并拒绝注册", conversationId);
            return false;
        }
        return true;
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
     * 停止一个会话：先走本地快路径，本地没有才广播给其它实例兜底。
     *
     * @return true 表示<b>本实例</b>确实停掉了一个在跑的任务；false 既可能是这个会话哪个实例
     *         都没在跑，也可能是它正在别的实例上跑、广播已经发出去但结果是异步的——调用方如果
     *         需要"确实停掉了"的确认，只能通过后续事件流（比如 Complete 事件）判断，不能只看这个返回值
     */
    public boolean stopTask(String conversationId) {
        boolean stoppedLocally = stopLocalTask(conversationId);
        if (!stoppedLocally && broadcaster != null) {
            log.debug("会话 {} 本实例没有找到，广播给其它实例尝试停止", conversationId);
            broadcaster.broadcastStop(conversationId);
        }
        return stoppedLocally;
    }

    /**
     * 硬停止：取消上游订阅 + 关闭下游事件流，丢弃全部运行时状态。
     *
     * <p>用 {@code remove} 的原子返回值判断任务是否存在，而不是"先 get 判断、再 remove"——
     * 后者在两步之间如果有新任务注册进来，会被误删（踩坑点 #10）。
     *
     * <p>本方法既是 {@link #stopTask} 本地路径的实现，也是收到跨实例广播后的回调——
     * 两条路径最终都要执行同一套"取消订阅 + 关闭事件流"逻辑，不能有第二份实现。
     *
     * @return true 表示确实停掉了一个在跑的任务
     */
    private boolean stopLocalTask(String conversationId) {
        TaskInfo task = taskMap.remove(conversationId);
        if (task == null) {
            log.warn("会话 {} 没有在跑的任务可停止", conversationId);
            return false;
        }
        disposeQuietly(task.disposable);
        task.sink.tryEmitComplete();
        releaseRedisLockQuietly(conversationId);
        log.debug("已停止会话 {} 的任务", conversationId);
        return true;
    }

    /** 任务正常跑完后的清理，不碰订阅和事件流——那两者此时已经自然结束了。 */
    public void removeTask(String conversationId) {
        taskMap.remove(conversationId);
        releaseRedisLockQuietly(conversationId);
    }

    /** 释放跨实例归属，让别的实例不用等 TTL 过期就能立刻接手同名会话；失败不影响任务本身已经收尾这件事。 */
    private void releaseRedisLockQuietly(String conversationId) {
        if (redisTaskLock == null) {
            return;
        }
        try {
            redisTaskLock.release(conversationId);
        } catch (RuntimeException failure) {
            log.warn("释放会话 {} 的跨实例锁失败，留给 TTL 自愈兜底: {}", conversationId, failure.getMessage());
        }
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
