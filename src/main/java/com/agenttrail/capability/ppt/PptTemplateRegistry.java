package com.agenttrail.capability.ppt;

import java.util.Optional;

/** 模板版本注册表；生产实现可以替换为数据库，状态机只依赖这份稳定接口。 */
public interface PptTemplateRegistry {
    void register(PptTemplateVersion template);

    Optional<PptTemplateVersion> find(String templateId, String version);

    Optional<PptTemplateVersion> active(String templateId);

    /** 校验文件与注册 checksum/契约一致，不合格模板不得进入任务上下文。 */
    PptTemplateVersion validate(String templateId, String version);
}
