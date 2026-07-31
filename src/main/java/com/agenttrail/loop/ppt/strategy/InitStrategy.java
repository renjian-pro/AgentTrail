package com.agenttrail.loop.ppt.strategy;

import com.agenttrail.loop.ppt.PptGenerationContext;
import com.agenttrail.loop.ppt.PptGenerationException;
import com.agenttrail.loop.ppt.PptGenerationStrategy;
import com.agenttrail.loop.ppt.PptState;

/**
 * INIT 状态（issue #24）——不调用任何外部服务，只做"这个任务能不能开始跑"的前置校验。
 * 单独作为一个状态而不是省略掉，是为了让"状态机分发表要覆盖全部状态"这条约束
 * （{@code PptGenerationService} 构造函数里的检查）从第一个状态开始就成立，不留特例。
 */
public class InitStrategy implements PptGenerationStrategy {

    @Override
    public PptState handledState() {
        return PptState.INIT;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        if (context.userRequirement() == null || context.userRequirement().isBlank()) {
            throw new PptGenerationException("PPT 生成需求不能为空");
        }
        return context;
    }
}
