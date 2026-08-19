package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptVisualPlan;

/**
 * VISUAL_PLAN 阶段把视觉决策单独 checkpoint 化。这样恢复时不会让 TEMPLATE/SCHEMA 各自
 * 重新猜颜色和字体，也让前端/审计可以明确知道视觉规划是否已经完成。
 */
public final class VisualPlanStrategy implements PptGenerationStrategy {

    @Override
    public PptState handledState() {
        return PptState.VISUAL_PLAN;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        return context.withVisualPlan(context.visualPlan() == null
                ? PptVisualPlan.defaultFor(context.requirement()) : context.visualPlan());
    }
}
