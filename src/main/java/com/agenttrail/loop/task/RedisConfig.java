package com.agenttrail.loop.task;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 生产 {@link RedissonClient} 装配（安全审计 2026-08-02 的 P0：{@code RedisTaskLock}/
 * {@code RedisInterruptBroadcaster} 一直都写好了，但从来没有一个真正的 {@link RedissonClient}
 * Bean 存在过——不只是"没接到 {@code AgentTaskManager} 上"这么简单，连 {@link
 * com.agenttrail.capability.analytics.config.AnalyticsSchemaConfig} 里那个 {@code
 * ObjectProvider<RedissonClient>} 也一直在拿 {@code null}，Analytics 的 schema 刷新锁同样从没
 * 真正生效过）。
 *
 * <p>默认不开启（{@code agenttrail.redis.enabled} 未设置时这个 Bean 根本不存在，{@code
 * ObjectProvider.getIfAvailable()} 拿到 null，所有消费方回退到"没有这个机制"的行为，和现状
 * 完全一致）——这台开发机没有常驻 Redis 服务（对比 MySQL 走 {@code SharedMySql} 那样的常驻实例，
 * Redis 相关测试全部用 Testcontainers 现起现拆），贸然把连接做成无条件必需会让本机 `mvn
 * spring-boot:run` 直接起不来。部署到有真实 Redis 的环境时，只需要把 {@code
 * agenttrail.redis.enabled=true} 和 {@code agenttrail.redis.address} 填进
 * {@code application-local.yml}（或对应环境的配置源），不需要再改一行代码。
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    @Bean
    @ConditionalOnProperty(prefix = "agenttrail.redis", name = "enabled", havingValue = "true")
    RedissonClient redissonClient(@Value("${agenttrail.redis.address:redis://127.0.0.1:6379}") String address) {
        Config config = new Config();
        config.useSingleServer().setAddress(address);
        return Redisson.create(config);
    }
}
