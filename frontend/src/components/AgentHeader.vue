<script setup lang="ts">
import { ref, watch } from 'vue'
import type { AgentKind } from '../stores/chat'

/**
 * 会话头部的 Agent 标识（issue #92）。两种形态由 `locked` 决定，**不是两个组件**——
 * 位置不变、只是从"可选"变成"只读"，用户视线不需要重新定位。
 *
 * <p>为什么常驻而不是像以前那样只在选中时提示一行"下一条消息将使用 XX"：一次性提示
 * 消失之后用户就不知道自己在哪个 Agent 下了，而模式发完还会静默复位——两件事叠起来
 * 就是"用户以为在数据分析、模型却在编数据"那条静默失败链路。
 *
 * <p><b>不再预告"发出第一条消息后即锁定"</b>：那句话出现在用户还什么都没做的时候，
 * 讲的是我们的实现约束（会话绑定执行器），而不是他的收益——第一眼看到界面就先读到一句
 * 看不懂的警告。约束改成**事后按需解释**：真的锁上之后，想换的人点「换一个」才会看到
 * 为什么要新开会话，以及一个能带着当前对话走的出口。
 */
const props = defineProps<{ agentKind: AgentKind; locked: boolean }>()
const emit = defineEmits<{ change: [AgentKind]; newFromHere: [] }>()

const LABELS: Record<AgentKind, string> = { chat: '普通对话', analytics: '数据分析' }
const MARKS: Record<AgentKind, string> = { chat: '○', analytics: '⌁' }

/** 「换一个」的解释是展开式的：不想换的人一眼都不用看见它。 */
const explaining = ref(false)
watch(() => props.locked, value => { if (!value) explaining.value = false })

function pick(kind: AgentKind) {
  if (props.locked || kind === props.agentKind) return
  emit('change', kind)
}
</script>

<template>
  <header class="agent-header">
    <template v-if="locked">
      <span class="agent-current"><i>{{ MARKS[agentKind] }}</i>本次会话：{{ LABELS[agentKind] }}</span>
      <button class="agent-fork" type="button" @click="explaining = !explaining">换一个</button>
      <p v-if="explaining" class="agent-explain">
        两种能力的工具不一样，中途换会让模型看到自己现在其实没有的工具，所以换要新开一个会话。
        <button type="button" @click="emit('newFromHere')">带着这段对话新开</button>
      </p>
    </template>
    <template v-else>
      <button v-for="kind in (['chat', 'analytics'] as AgentKind[])" :key="kind" type="button"
          class="agent-option" :class="{ active: kind === agentKind }" @click="pick(kind)">
        <i>{{ MARKS[kind] }}</i>{{ LABELS[kind] }}
      </button>
    </template>
  </header>
</template>
