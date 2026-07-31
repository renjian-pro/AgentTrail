package com.agenttrail.web;

import com.agenttrail.loop.deepresearch.DeepResearchReport;
import com.agenttrail.loop.deepresearch.DeepResearchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * DeepResearch 的 HTTP 入口（issue #25）。同步接口——需求澄清→主题生成→逐个任务检索→
 * 综合报告全部跑完才返回，符合这一票"最小骨架"的定位（先不做流式进度推送）。
 */
@RestController
public class DeepResearchController {

    private final DeepResearchService deepResearchService;

    public DeepResearchController(DeepResearchService deepResearchService) {
        this.deepResearchService = deepResearchService;
    }

    @PostMapping("/agent/v1/deepresearch")
    public DeepResearchReport research(@RequestBody DeepResearchRequest request) {
        return deepResearchService.research(request.question());
    }
}
