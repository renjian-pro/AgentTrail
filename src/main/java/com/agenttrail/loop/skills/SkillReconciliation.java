package com.agenttrail.loop.skills;

import java.util.List;

/**
 * 一次文件系统 ↔ 数据库对账的结果。
 *
 * <p>定时任务本可以什么都不返回，但对账是"悄悄改数据"的操作——尤其 {@link #orphansRemoved()}
 * 是真删记录。把每一轮到底动了什么原样返回出来，日志能打、测试能断言、
 * 出事时能回答"那条记录是哪一轮对账删的"。
 *
 * @param discovered    文件系统上新出现、这一轮入库的技能（默认启用）
 * @param orphansRemoved 数据库里有、但目录已经不在了的孤儿记录，这一轮删掉的
 * @param refreshed     两边都有、且描述或路径与磁盘不一致、这一轮按磁盘刷新过的
 */
public record SkillReconciliation(List<String> discovered, List<String> orphansRemoved, List<String> refreshed) {

    public SkillReconciliation {
        discovered = List.copyOf(discovered);
        orphansRemoved = List.copyOf(orphansRemoved);
        refreshed = List.copyOf(refreshed);
    }

    /** 扫描失败时用它——明确表达"这一轮什么都没动"，而不是返回 null。 */
    public static SkillReconciliation none() {
        return new SkillReconciliation(List.of(), List.of(), List.of());
    }
}
