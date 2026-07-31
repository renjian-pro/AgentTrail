package com.agenttrail.loop.ppt.strategy;

import com.agenttrail.loop.ppt.PptGenerationContext;
import com.agenttrail.loop.ppt.PptGenerationStrategy;
import com.agenttrail.loop.ppt.PptState;

import java.util.List;

/**
 * SEARCH 状态（issue #24）——这一票明确用简化/canned 输入，不接真实联网搜索：真实联网搜索
 * 集成是 issue #29 的范围，这里只需要证明"这个状态存在、会产出素材、下游 OUTLINE 状态能吃到它"
 * 这条链路是通的。
 *
 * <p>不调用任何外部服务（不是 LLM 调用、也不是网络请求），素材是根据
 * {@link com.agenttrail.loop.ppt.PptRequirement} 里的 topic/audience 拼出来的固定句式——
 * 换成真实搜索时，替换的是这一个类的实现，{@link PptGenerationStrategy} 接口和上下游状态
 * 完全不用动。
 */
public class SearchStrategy implements PptGenerationStrategy {

    @Override
    public PptState handledState() {
        return PptState.SEARCH;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        var requirement = context.requirement();
        List<String> materials = List.of(
                "关于主题「%s」的背景信息：面向%s，需要覆盖核心概念、现状和典型应用场景。"
                        .formatted(requirement.topic(), requirement.audience()),
                "关于主题「%s」的补充要点：可以从行业趋势、关键数据、代表性案例三个角度组织内容。"
                        .formatted(requirement.topic()));
        return context.withSearchMaterials(materials);
    }
}
