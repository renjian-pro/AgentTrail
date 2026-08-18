package com.agenttrail.loop.tools.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * 幂等记录的存储抽象。
 *
 * <p>接口只有四个方法，每个方法都能被 Redis 和关系库**原生**实现，不需要额外的应用层锁——
 * 这是这个接口最重要的设计约束。单机版（{@link InMemoryIdempotencyStore}）只是第一个实现，
 * 分布式部署后换成 Redis/JDBC 实现，装饰器一行都不用改：
 *
 * <table border="1">
 *   <caption>各实现的落地方式</caption>
 *   <tr><th>方法</th><th>Redis</th><th>JDBC（MySQL）</th></tr>
 *   <tr><td>{@link #claim}</td>
 *       <td>{@code SET key IN_FLIGHT NX PX <lease>}，返回 OK 即抢到</td>
 *       <td>{@code INSERT} 撞唯一索引即没抢到</td></tr>
 *   <tr><td>{@link #complete}</td>
 *       <td>{@code SET key <result> PX <ttl>}</td>
 *       <td>{@code UPDATE ... SET status='COMPLETED'}</td></tr>
 *   <tr><td>{@link #release}</td>
 *       <td>{@code DEL key}</td><td>{@code DELETE WHERE status='IN_FLIGHT'}</td></tr>
 *   <tr><td>{@link #find}</td><td>{@code GET key}</td><td>{@code SELECT}</td></tr>
 * </table>
 *
 * <p><b>为什么 TTL / 租约时长是方法参数而不是实现的构造参数</b>：过期策略属于**调用方的业务语义**
 * （"同一笔付款 24 小时内不许重复提交"是业务规则，不是存储配置），而且这样能直接映射到
 * Redis 的 {@code PX} 参数上，不用实现自己再维护一套过期扫描。
 */
public interface IdempotencyStore {

    /**
     * 原子地尝试占位一个幂等键。
     *
     * <p>这是整个模板唯一需要原子性的地方。<b>不能</b>拆成"先 find 再 put"——那是典型的
     * check-then-act 竞态，两个线程/两个实例会同时认为自己是首次执行者，
     * 副作用照样做两遍（同 {@code AgentTaskManager} 的单飞注册）。
     *
     * @param key          完整幂等键
     * @param leaseTimeout 占位租约时长：超过这个时间还停在 IN_FLIGHT，视为执行者已死，
     *                     下一个调用方可以重新抢占。设太短会导致真正在执行的调用被重复执行，
     *                     设太长会让崩溃后的键长时间不可用——按"工具最坏执行耗时"取值
     * @return 抢到时返回不可伪造的 owner token；没抢到时返回当前已存在的记录。租约被新执行者
     *         接管后，旧 token 不能完成或释放新租约，避免迟到写覆盖当前所有者。
     */
    IdempotencyClaim claim(String key, Duration leaseTimeout);

    /**
     * 把占位记录改写成"已完成 + 结果"，此后同键调用直接回放 {@code result}。
     *
     * @param ownerToken {@link #claim} 返回的租约所有权 token
     * @param recordTtl  去重窗口：多久之后这条记录过期、同一个键重新变得可执行
     */
    void complete(String key, String ownerToken, String result, Duration recordTtl);

    /** 只释放 ownerToken 自己仍持有的 IN_FLIGHT 占位，让失败调用可重试且不伤到接管者。 */
    void release(String key, String ownerToken);

    /** 查询当前记录；已过期的记录视为不存在。 */
    Optional<IdempotencyRecord> find(String key);
}
