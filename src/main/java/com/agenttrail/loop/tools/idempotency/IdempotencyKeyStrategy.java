package com.agenttrail.loop.tools.idempotency;

import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Optional;

/**
 * 从一次工具调用里推导幂等键。
 *
 * <p>幂等键从哪来，决定了"什么算同一次调用"这个语义，是套用本模板时唯一必须想清楚的问题：
 * <ul>
 *   <li><b>参数摘要</b>（{@link IdempotencyKeyStrategies#argumentDigest()}）——
 *       "参数完全一样就算同一次"。零改造成本，但对参数噪声敏感：模型重新生成一遍参数时
 *       多打了个空格、或者系统注入了时间戳，摘要就变了，去重失效。</li>
 *   <li><b>显式 token</b>（{@link IdempotencyKeyStrategies#argumentField}）——
 *       调用方（模型或上游业务）在参数里带一个 {@code requestId}，"token 一样就算同一次"。
 *       语义最准，但要求工具的 schema 里真的有这个字段，且调用方在重试时**复用**同一个 token。</li>
 * </ul>
 *
 * <p>返回 {@link Optional#empty()} 表示"这次调用推导不出幂等键"。装饰器此时会
 * <b>直接透传执行</b>而不是报错——因为一个推导不出键的调用，重复执行和不执行同样不安全，
 * 拦下来只会让工具彻底不可用；正确的做法是在日志里报警、把缺失的 token 补上。
 */
@FunctionalInterface
public interface IdempotencyKeyStrategy {

    /**
     * @param toolInput      工具收到的原始 JSON 参数字符串
     * @param toolDefinition 工具定义，需要按工具名/schema 做差异化推导时用
     * @return 幂等键（不含工具名前缀，前缀由装饰器统一加），推导不出时返回空
     */
    Optional<String> deriveKey(String toolInput, ToolDefinition toolDefinition);
}
