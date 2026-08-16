package com.agenttrail.loop.prompt;

/**
 * 一份外置提示词（issue #100）。
 *
 * @param id      稳定标识，形如 {@code deepresearch.clarification}；跨版本不变，trace 按它归因
 * @param version 手工语义版本，给人看先后
 * @param hash    正文 SHA-256 前 8 位，**真实标识**——版本号会有人忘记改，哈希不会
 * @param text    提示词正文（front matter 之后的部分，已 strip）
 */
public record PromptDefinition(String id, String version, String hash, String text) {

    /** 写进 trace 的形式：{@code id@version#hash}。三段都要，缺一段就少一种归因能力。 */
    public String stamp() {
        return id + "@" + version + "#" + hash;
    }
}
