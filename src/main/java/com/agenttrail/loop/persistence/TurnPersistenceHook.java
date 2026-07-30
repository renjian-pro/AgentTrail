package com.agenttrail.loop.persistence;

/**
 * 一轮问答结束时的持久化回调。
 *
 * <p>循环内核不认识数据库——存哪、怎么存全是调用方的事，内核只在"这一轮真正结束"的那一刻
 * 同步回调一次，并拿回落库产生的主键，好让 Complete 事件带上它（前端要用这个 id 关联附件）。
 *
 * <p><b>调用时机是硬约束</b>：必须在事件流关闭**之前**同步完成，不能挂到流的收尾回调里。
 * 挂在收尾回调里的写法踩过真实事故——进程退出时收尾回调没来得及跑完，那一轮的历史直接丢了
 * （踩坑点 #63）。
 */
public interface TurnPersistenceHook {

    /**
     * @return 落库后的单轮记录 id；实现方无法提供时返回 null，此时 Complete 事件不带 id
     */
    Long onTurnComplete(TurnRecord record);
}
