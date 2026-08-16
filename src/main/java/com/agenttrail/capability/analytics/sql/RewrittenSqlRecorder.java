package com.agenttrail.capability.analytics.sql;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 记录最近一次**数据权限改写之后**的 SQL（issue #97）。
 *
 * <p>为什么需要它：评测的权限维度必须断言"服务端注入了范围过滤"，而 Golden harness 此前取的是
 * {@code ToolStart} 参数里模型自己写的 SQL——那是改写**之前**的。用它断言 {@code dept_id} 存在，
 * 等于在奖励模型违反 SKILL.md（"不能手写 dept_id 或 user_id 权限条件，服务端会自动改写"）：
 * 模型听话则断言失败，不听话才通过。跑出来的通过率无论多少都说明不了权限改写有没有生效。
 *
 * <p><b>只保存最后一条，仅供顺序执行的评测使用。</b>{@code ExecuteSqlTool} 的 sink 是一个
 * {@code Consumer<String>}，拿不到 conversationId，没法按会话分桶。Golden harness 是逐条串行跑的
 * （见 {@code GoldenTaskRunner.run}），这个前提下"最后一条"就是"这条用例的那条"。生产装配里它
 * 照常挂着但没人读，开销是一次引用赋值。
 *
 * <p>要在并发场景里用它（比如做成审计能力），必须先改成按会话分桶——不要直接拿来用。
 */
public final class RewrittenSqlRecorder implements Consumer<String> {

    private final AtomicReference<String> last = new AtomicReference<>();

    @Override
    public void accept(String rewrittenSql) {
        last.set(rewrittenSql);
    }

    /** 取走并清空——清空是必须的：不清的话某条用例压根没执行 SQL 时，会读到上一条用例的残留。 */
    public Optional<String> takeLast() {
        return Optional.ofNullable(last.getAndSet(null));
    }
}
