package com.agenttrail.loop.memory;

/**
 * 一条跨会话的长期记忆（issue #19）——分层记忆体系里"用户画像/偏好"这一层的存储单元。
 *
 * <p>比 agentx-core 的同名类精简：去掉了 id（这一层不需要按条目单独查找/删除，
 * 只需要按 userId 整体读取和追加）、description、metadata、updatedAt 这些当前用不上的字段。
 *
 * @param userId          所属用户
 * @param type            记忆类型
 * @param content         记忆正文
 * @param createdAtMillis 提取时刻
 */
public record MemoryItem(String userId, MemoryType type, String content, long createdAtMillis) {
}
