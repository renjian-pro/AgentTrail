<script setup lang="ts">
import type { AgentKind } from '../stores/chat'

/**
 * 会话头部的 Agent 标识（issue #92）。两种形态由 `locked` 决定，**不是两个组件**——
 * 位置不变、只是从"可选"变成"只读"，用户视线不需要重新定位。
 *
 * <p>为什么常驻而不是像以前那样只在选中时提示一行"下一条消息将使用 XX"：一次性提示
 * 消失之后用户就不知道自己在哪个 Agent 下了，而模式发完还会静默复位——两件事叠起来
 * 就是"用户以为在数据分析、模型却在编数据"那条静默失败链路。
 */
const props = defineProps<{ agentKind: AgentKind; locked: boolean }>()
const emit = defineEmits<{ change: [AgentKind]; newFromHere: [] }>()

const LABELS: Record<AgentKind, string> = { chat: '普通对话', analytics: '数据分析' }
const MARKS: Record<AgentKind, string> = { chat: '○', analytics: '⌁' }

function pick(kind: AgentKind) {
  if (props.locked || kind === props.agentKind) return
  emit('change', kind)
}
</script>

<template>
  <header class="agent-header">
    <template v-if="locked">
      <span class="agent-current" :title="`本会话已绑定「${LABELS[agentKind]}」，换 Agent 请新建会话`">
        <i>{{ MARKS[agentKind] }}</i>{{ LABELS[agentKind] }}
      </span>
      <button class="agent-fork" @click="emit('newFromHere')">基于此会话新建</button>
    </template>
    <template v-else>
      <span class="agent-label">选择能力</span>
      <button v-for="kind in (['chat', 'analytics'] as AgentKind[])" :key="kind" type="button"
          class="agent-option" :class="{ active: kind === agentKind }" @click="pick(kind)">
        <i>{{ MARKS[kind] }}</i>{{ LABELS[kind] }}
      </button>
      <span class="agent-note">发出第一条消息后即锁定</span>
    </template>
  </header>
</template>
