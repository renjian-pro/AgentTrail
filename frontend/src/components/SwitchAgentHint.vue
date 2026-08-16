<script setup lang="ts">
/**
 * 普通对话里问了数据问题时的引导卡片（issue #94）。
 *
 * <p>做成对话流里的 inline 卡片而不是 toast：toast 会消失、不可回溯，而这条信息解释的是
 * "接下来这个回答为什么可能不靠谱"，用户回头翻记录时仍然需要看到它。
 *
 * <p>它同时是会话级 Agent 绑定的配套出口——用户不需要理解"为什么要开新会话"，点一下就行。
 */
const emit = defineEmits<{ switchToAnalytics: [] }>()
</script>

<template>
  <aside class="switch-hint">
    <p>
      <strong>这个问题需要查询真实数据。</strong>
      当前会话是普通对话，没有数据库工具，回答里的数字可能是模型自己推断的。
    </p>
    <button type="button" @click="emit('switchToAnalytics')">用数据分析新建会话</button>
  </aside>
</template>
